package com.instaclustr.backup.uploader;

import com.ibm.cloud.objectstorage.AmazonClientException;
import com.ibm.cloud.objectstorage.AmazonServiceException;
import com.ibm.cloud.objectstorage.client.builder.AwsClientBuilder;
import com.ibm.cloud.objectstorage.event.ProgressEventType;
import com.ibm.cloud.objectstorage.event.ProgressListener;
import com.ibm.cloud.objectstorage.services.s3.AmazonS3;
import com.ibm.cloud.objectstorage.services.s3.AmazonS3ClientBuilder;
import com.ibm.cloud.objectstorage.services.s3.model.*;
import com.ibm.cloud.objectstorage.services.s3.transfer.TransferManager;
import com.ibm.cloud.objectstorage.services.s3.transfer.TransferManagerBuilder;
import com.ibm.cloud.objectstorage.services.s3.transfer.Upload;
import com.google.common.base.Optional;
import com.instaclustr.backup.BackupArguments;
import com.instaclustr.backup.common.RemoteObjectReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZonedDateTime;

public class IBMSnapshotUploader extends SnapshotUploader {
    private static final Logger logger = LoggerFactory.getLogger(IBMSnapshotUploader.class);

    private final TransferManager transferManager;
    private final AmazonS3 amazonS3;

    private final Optional<String> kmsId;

    public IBMSnapshotUploader(final BackupArguments arguments) {
        super(arguments.clusterId, arguments.backupId, arguments.backupBucket);
        AwsClientBuilder.EndpointConfiguration ec = new AwsClientBuilder.EndpointConfiguration(arguments.endpoint, System.getenv("AWS_REGION"));
        this.amazonS3 = AmazonS3ClientBuilder.standard().withEndpointConfiguration(ec).build();
        this.transferManager = TransferManagerBuilder.standard().withS3Client(this.amazonS3).build();
        this.kmsId = Optional.absent();
    }

    static class AWSRemoteObjectReference extends RemoteObjectReference {
        public AWSRemoteObjectReference(Path objectKey, String canonicalPath) {
            super(objectKey, canonicalPath);
        }

        @Override
        public Path getObjectKey() {
            return objectKey;
        }
    }

    @Override
    public RemoteObjectReference objectKeyToRemoteReference(final Path objectKey) {
        return new AWSRemoteObjectReference(objectKey, resolveRemotePath(objectKey));
    }

    @Override
    public FreshenResult freshenRemoteObject(final RemoteObjectReference object) throws InterruptedException {
        final String canonicalPath = ((AWSRemoteObjectReference) object).canonicalPath;

        final CopyObjectRequest copyRequest = new CopyObjectRequest(restoreFromBackupBucket, canonicalPath, restoreFromBackupBucket, canonicalPath)
                .withStorageClass(System.getenv("AWS_REGION") + "-standard")
                .withMetadataDirective("REPLACE");

        if (kmsId.isPresent()) {
            final SSEAwsKeyManagementParams params = new SSEAwsKeyManagementParams(kmsId.get());
            copyRequest.withSSEAwsKeyManagementParams(params);
        }

        try {
            // attempt to refresh existing object in the bucket via an inplace copy
            transferManager.copy(copyRequest).waitForCompletion();
            return FreshenResult.FRESHENED;

        } catch (final AmazonServiceException e) {
            // AWS S3 under certain access policies can't return NoSuchKey (404)
            // instead, it returns AccessDenied (403) — handle it the same way
            if (e.getStatusCode() != 404 && e.getStatusCode() != 403) {
                throw e;
            }

            // the freshen failed because the file/key didn't exist
            return FreshenResult.UPLOAD_REQUIRED;
        }
    }

    @Override
    public void uploadSnapshotFile(final long size, final InputStream localFileStream, final RemoteObjectReference object) throws Exception {
        final AWSRemoteObjectReference awsRemoteObjectReference = (AWSRemoteObjectReference) object;

        final PutObjectRequest putObjectRequest = new PutObjectRequest(restoreFromBackupBucket, awsRemoteObjectReference.canonicalPath, localFileStream,
                new ObjectMetadata() {{
                    setContentLength(size);
                }}
        );

        if (kmsId.isPresent()) {
            final SSEAwsKeyManagementParams params = new SSEAwsKeyManagementParams(kmsId.get());
            putObjectRequest.withSSEAwsKeyManagementParams(params);
        }

        final Upload upload = transferManager.upload(putObjectRequest);

        upload.addProgressListener((ProgressListener) progressEvent -> {
            if (progressEvent.getEventType() == ProgressEventType.TRANSFER_PART_COMPLETED_EVENT)
                logger.debug("Successfully uploaded part for {}.", awsRemoteObjectReference.canonicalPath);
        });

        upload.waitForCompletion();
    }

    @Override
    void cleanup() throws Exception {
        try {
            // TODO cleanupMultipartUploads gets access denied, INS-2326 is meant to fix this
            cleanupMultipartUploads();

        } catch (Exception e) {
            logger.warn("Failed to cleanup multipart uploads.", e);
        }
        amazonS3.shutdown();
    }

    private void cleanupMultipartUploads() {
        final Instant yesterdayInstant = ZonedDateTime.now().minusDays(1).toInstant();

        logger.info("Cleaning up multipart uploads older than {}.", yesterdayInstant);

        final ListMultipartUploadsRequest listMultipartUploadsRequest = new ListMultipartUploadsRequest(restoreFromBackupBucket)
                .withPrefix(restoreFromClusterId);

        while (true) {
            final MultipartUploadListing multipartUploadListing = amazonS3.listMultipartUploads(listMultipartUploadsRequest);

            multipartUploadListing.getMultipartUploads().stream()
                    .filter(u -> u.getInitiated().toInstant().isBefore(yesterdayInstant))
                    .forEach(u -> {
                        logger.info("Aborting multi-part upload for key \"{}\" initiated on {}", u.getKey(), u.getInitiated().toInstant());

                        try {
                            amazonS3.abortMultipartUpload(new AbortMultipartUploadRequest(restoreFromBackupBucket, u.getKey(), u.getUploadId()));

                        } catch (final AmazonClientException e) {
                            logger.error("Failed to abort multipart upload for key \"{}\".", u.getKey(), e);
                        }
                    });

            if (!multipartUploadListing.isTruncated())
                break;

            listMultipartUploadsRequest
                    .withKeyMarker(multipartUploadListing.getKeyMarker())
                    .withUploadIdMarker(multipartUploadListing.getUploadIdMarker());
        }
    }
}

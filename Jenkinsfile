@Library("icm-jenkins-common")
import com.ibm.icm.*

node {
    GitInfo gitInfo = icmCheckoutStages()
    sh "mvn deploy"
}

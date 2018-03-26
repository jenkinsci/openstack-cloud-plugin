package jenkins.plugins.openstack.pipeline

pipeline {
    agent {
        openstack {
            cloud 'openstack'
            bootSource $class: 'Image', name: 'bootSource-image-name'
            hardwareId 'harwareId'
            networkId 'networkId'
            userDataId 'userDataId'
            floatingIpPool 'floatingIpPool'
            securityGroups 'securityGroups'
            availabilityZone 'availabilityZone'
            startTimeout 100000
            keyPairName 'keyPairName'
            jvmOptions 'jvmOptions'
            fsRoot 'fsRoot'
            launcherFactory $class: 'JNLP'
        }
    }
    stages {
        stage('Example Build') {
            steps {
                echo 'Hello World!'
            }
        }
    }
}
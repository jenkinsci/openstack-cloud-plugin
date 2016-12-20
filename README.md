# OpenStack Cloud Jenkins Plugin

Provision slaves from OpenStack on demand.

## Testing

The plugin has an integration tests implemented using Acceptance Test Harness. We are looking for volunteers to run that in their environments to make sure the plugin works against their openstack.

    $ git clone https://github.com/jenkinsci/acceptance-test-harness.git
    $ cd acceptance-test-harness
    $ eval $(./vnc.sh)
    $ ./run.sh firefox lts -DOpenstackCloudPluginTest.ENDPOINT=<http://my.openstack/v2.0>\
        -DOpenstackCloudPluginTest.IDENTITY=<identity>\
        -DOpenstackCloudPluginTest.CREDENTIAL=<password>\
        -DOpenstackCloudPluginTest.HARDWARE_ID=<hardware-it-to-use>\
        -DOpenstackCloudPluginTest.IMAGE_ID=<image-id>\
        -DOpenstackCloudPluginTest.KEY_PAIR_NAME=<key-pair>\
        -DOpenstackCloudPluginTest.FIP_POOL_NAME=<floating-ip-pool>\
        -Dtest=OpenstackCloudPluginTest
        

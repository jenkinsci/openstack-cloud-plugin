# OpenStack Cloud plugin for Jenkins

Provision nodes from OpenStack on demand.
  
[![Build Status](https://ci.jenkins.io/job/Plugins/job/openstack-cloud-plugin/job/master/badge/icon)](https://ci.jenkins.io/job/Plugins/job/openstack-cloud-plugin/job/master/)

## Essentials

<a href="https://raw.githubusercontent.com/jenkinsci/openstack-cloud-plugin/master/docs/config.png"><img align="right" width="300" src="https://raw.githubusercontent.com/jenkinsci/openstack-cloud-plugin/master/docs/config.png"></a>
In order to provision new nodes when Jenkins load goes high,
administrator needs to configure *Cloud* and one or more *Templates* on
global configuration page. Cloud represents connection to particular
OpenStack project (tenant). In order to use several openstack instances
or projects, it is necessary to configure several Clouds in Jenkins.
Template can be seen as a definition of particular *kind* of node.
Template is the primary holder of all the attributes for OpenStack
machine to be provisioned (image, flavor, etc.) as well as for Jenkins
node (number of executors, etc.). Note the attributes
can be configured on Cloud level as well. Such configuration will be
then used as default for its templates. For example, if all nodes are
supposed to use the same key-pair there is no need to state it in every
template, it can be configured on cloud level and leave the filed blank
in the templates.

<a href="https://raw.githubusercontent.com/jenkinsci/openstack-cloud-plugin/master/docs/options.png"><img align="right" width="300" src="https://raw.githubusercontent.com/jenkinsci/openstack-cloud-plugin/master/docs/options.png"></a>
Aside from machine/node attributes, every template require name and
labels to be configured. Name will serve both as an identifier of the
template as well as a name prefix for Jenkins node and OpenStack machine
(that is why some limitations apply here). Labels field expects a set of
Jenkins labels that will be assigned to all nodes that the template
provisions. It will also be used to determine which cloud and template to
use to process Jenkins load. When there is a build with no label
requirements, any
template can be used to provision the node. Build with label restriction
can trigger provisioning only on templates with matching label set. The
attributes at template level will inherit all global values (the value
in effect is printed under the field on hte config page). In case required
field do not have a default nor current value, it will be reported.

The exception is here is the *Instance Cap* field that determines the maximal
ammount of maches to be running either per whole cloud or per template. This is
so one can limit the total number and number per individual template at the same time.

### User data

Every template can declare user-data script to be passed to
[cloud-init](https://cloudinit.readthedocs.io/en/latest/index.html) to customize
the machine that is provisioned. Note that the image needs to support
cloud init explicitly.

Before the script is sent to OpenStack, plugin injects several values
using `${VARIABLE_NAME}` syntax:

-   **`JENKINS_URL`**: The URL of the Jenkins instance
-   **`SLAVE_JAR_URL`**: URL of the slave.jar - the agent executable
-   **`SLAVE_JNLP_URL`**: The endpoint URL for JNLP connection
-   **`SLAVE_JNLP_SECRET`**: The JNLP 'secret' key. This key authorizes
    to connect Jenkins agent process to a Jenkins computer. Note that
    referencing this in server user-data (and then using it to launch
    agent process) makes it exposed to any user or automation permitted
    to access the Jenkins agent (including running builds). Also, when
    OpenStack deployment uses metadata service, which it often does, it
    must be deployed in a way this is guaranteed not to leak. See
    [OSSN-0074](https://wiki.openstack.org/wiki/OSSN/OSSN-0074) for
    further details.
-   **`SLAVE_LABELS`**: Labels of the corresponding Jenkins computer

### Reporting

Openstack plugin utilizes [Cloud Statistics](https://plugins.jenkins.io/cloud-stats) that captures failures and time trends of past provisioning attempts.

### Configuration As Code

Since version 2.46, [JCasC](https://jenkins.io/projects/jcasc/) is supported. Example:

```yaml
jenkins:
  clouds:
    - openstack:
        name: "foo"
        endPointUrl: "https://acme.com:5000"
        credentialsId: "openstack_service_credentials"
        ignoreSsl: false
        zone: foo
        slaveOptions:
          bootSource:
            image:
              name: "Image Name"
          hardwareId: "hid"
          networkId: "net"
          userDataId: "user-data-id"
          instanceCap: 11
          instancesMin: 1
          floatingIpPool: "baz"
          securityGroups: "s1,s2"
          availabilityZone: "bax"
          startTimeout: 15
          keyPairName: "key"
          numExecutors: 2
          jvmOptions: "-Xmx1G"
          fsRoot: "/tmp/foo"
          launcherFactory:
            ssh:
              credentialsId: "openstack_ssh_key"
              javaPath: "/bin/true"
          retentionTime: 42
        templates:
          - name: "empty"
            labels: "linux"
          - name: "jnlp"
            labels: "jnlp"
            slaveOptions:
              launcherFactory: "jnlp"
          - name: "volumeSnapshot"
            labels: "volume"
            slaveOptions:
              bootSource:
                volumeSnapshot:
                  name: "Volume name"
          - name: "volumeFromImage"
            labels: "volume from image"
            slaveOptions:
              bootSource:
                volumeFromImage:
                  name: "Volume name"
                  volumeSize: 15
 
unclassified:
  globalConfigFiles:
    configs:
      - openstackUserData:
          id: user-data-id
          name: "My user data"
          comment: "... with a comment"
          content: >
            #cloud-config
            disable_root: 0
            ssh_pwauth: True
            chpasswd: { expire: False }
 
            users:
              - name: root
                password: toor
                lock-passwd: false
                inactive: false
                system: false
 
credentials:
  system:
    domainCredentials:
      - credentials:
          - openstackV3:
              scope: SYSTEM
              id: "openstack_service_credentials"
              description: "desc"
              userName: "foo"
              userDomain: "acme.com"
              projectName: "casc"
              projectDomain: "acme.com"
              password: "bar" # Do not hardcode plaintext secrets for real world declarations!
          - openstackV2:
              scope: SYSTEM
              id: "openstack_service_credentialsV2"
              description: "desc"
              username: "username"
              password: "pwd" # Do not hardcode plaintext secrets for real world declarations!
              tenant: "tnt"
          - basicSSHUserPrivateKey:
              scope: SYSTEM
              id: "openstack_ssh_key"
              username: "jenkins"
              privateKeySource:
                directEntry:
                  # Do not hardcode plaintext secrets for real world declarations!
                  privateKey: >
                    -----BEGIN OPENSSH PRIVATE KEY-----
                    b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAABFwAAAAdzc2gtcn
                    NhAAAAAwEAAQAAAQEAleOyx/pWbWBWrKOXyDpXio33Y6jAXdAi2mqo1nKIcIX75U71WxcR
                    2+i+IqlyVm85YcBQ3xKZ9KVxW9rCGn/KNJkQdQa+hGltJUHJNLPGCoZG0Qj5LLhXW3SSOQ
                    3X2e8FMLTmrHeBqOZhFJr9ijlDX23Hbo5JENGs8MCXAfFBcthBViWWouaon2qgH1xncT19
                    OVpQbAozwgqM1pl+6fL1yvBw89emAix+G+iy+r89fk+mb/5jwkikFsk9qhZrQIkrSsGS2h
                    noH+LeRUtMyDcjXcqC214PtyI38hA+TxjIfqNBz8VF9juhAq28kOVFMPBNxoI8bW2F6/1h
                    49Jkg9iLzQAAA8i2KKHJtiihyQAAAAdzc2gtcnNhAAABAQCV47LH+lZtYFaso5fIOleKjf
                    djqMBd0CLaaqjWcohwhfvlTvVbFxHb6L4iqXJWbzlhwFDfEpn0pXFb2sIaf8o0mRB1Br6E
                    aW0lQck0s8YKhkbRCPksuFdbdJI5DdfZ7wUwtOasd4Go5mEUmv2KOUNfbcdujkkQ0azwwJ
                    cB8UFy2EFWJZai5qifaqAfXGdxPX05WlBsCjPCCozWmX7p8vXK8HDz16YCLH4b6LL6vz1+
                    T6Zv/mPCSKQWyT2qFmtAiStKwZLaGegf4t5FS0zINyNdyoLbXg+3IjfyED5PGMh+o0HPxU
                    X2O6ECrbyQ5UUw8E3GgjxtbYXr/WHj0mSD2IvNAAAAAwEAAQAAAQBJrcbZBFZtp3iTnkri
                    8sLLaeOcinwc4U3wnZNm7p/g6AudeYWkBCAUQEEOWsrIcB39zgIy1Tr2hkjFxS+6xOxJlK
                    ABVpJaFlS/hqn4DRKhY8X1xPpvICJY42FpSEO9bf/YJGRrjMcgljZMYa+VvXY/t3/b+Xcz
                    HE5tfc3893GbmK9YUvFu6WdGg/3J3M/L3NvJVlPDfq7hQkx1EPVv/w5B+CNrVRayKypVRj
                    3cV/akjVuSblOs227nFzEtt6WDFky7H0T7rwoJKT0Co+4gVheQGibzU726MdXgVI2W2SPo
                    h3HcQfA74FLi6JeM1s/Fkl4UZctbxzXXrtW3v8ecEbEhAAAAgFc8FdBS7Jbo+ofOgfmTBE
                    fCkvVU/TIvPrkz6KAJxuBaYYGpT+YtSoJwpmdjOn0M23KiDA/4i+1G/NVZXa/N22rUd9Gp
                    uSikOImAwrhB3hjr5c/8lt+iC89fdWQBEZs2QsxLeHPIqNnjYlyDa6Rz0t4lQe54rmbtXR
                    FYgMGGBglTAAAAgQDEYxhyBdzLMg1U8XTe6rQ41ikPuePdeUghP0JCjq7M5TzdTRCC1oDe
                    vwREeLNFOvDLdl8sqYGJTegpdVM2FHIQBmbxamM2Ms0YSETsCMWUHJguh3mKvIx8ICPkoZ
                    eNN2HlSxh2ug3unE9vANuJKZztAsnPMoafMPyKmH5XbL+F1QAAAIEAw2NZu/9a815Rwr7C
                    JbAt3jdjyM6MIVAFb7BQS1wtGsitZCaeb0Pond+T4j7mOTbzMZhE0lstwsWWrDWlX9LkHV
                    RsDHpCNTnxRfQeA1NL5LoTIW8OfjV2/NiGAa6INerBBURRKlIRJYdLmdi/IoNSzwBzC/mV
                    kh2nsVg0sOMNkhkAAAAMb2dvbmR6YUBhcmNoAQIDBAUGBw==
                    -----END OPENSSH PRIVATE KEY-----
```
  

### Tips and tricks

-   User can manually provision slave from particular cloud/template on
    *Manage Jenkins \> Manage Nodes.*
-   Plugin identifies OpenStack image/snapshot to provision by its name.
    The image can be updated/replaced in openstack and the plugin will
    not require update provided its name have not changed.
-   In case maximal instance number is specified on template level, the
    stricter of the two (global and template value) will be applied.
    Global value exists to ensure that the number of machines
    provisioned by plugin will not exceed certain limit. The template
    value can further restrict that at most N machines of given kind can
    be utilized at the same time.
-   Plugin can report maximal number of instances was reached while
    there is not adequate number of Jenkins nodes. It is because plugin
    inspects running OpenStack machines in order to cover machines being
    provisioned/deleted (that do not have Jenkins nodes) and instances
    that plugin failed to delete. In case the instance get leaked,
    please report that as a bug with all relevant FINE level logs
    attached.
-   On every slave provisioned by the plugin, there is an environment
    variable `OPENSTACK_PUBLIC_IP` declared with the public IP address
    allocated for the machine.

### Troubleshooting

#### Accessing logs

Plugin uses `INFO` and above levels to report things user should worry
about. For debugging, set it to `FINEST` - note the `ALL` level is not
sufficient to print these. To configure OpenStack plugin logging in
Jenkins UI go to *Manage Jenkins \> System Log \> New Log Recorder* and
use `jenkins.plugins.openstack.compute` as the logger name.

#### Access openstack client from groovy console

Use `Jenkins.instance.clouds[0].openstack.@clientProvider.get()` to
access the openstack4j client. Users are discouraged to use this
anything else but querying the openstack (otherwise there is no way to
ensure plugin will work correctly). For older versions fo the plugin use
`Jenkins.instance.clouds[0].openstack.@client`.

#### User Data / Cloud init is not evaluated

The image might not support it at all or can fail executing it. Check
machine log in OpenStack for further details. Note that for now, there
is no guarantee the script will complete before machine is connected to
Jenkins and builds are started.

### Changelog

[Releases Page](https://github.com/jenkinsci/openstack-cloud-plugin/releases)

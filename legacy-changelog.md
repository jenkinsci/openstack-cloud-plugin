## Changelog

##### Version 2.47

-   [Issue](https://github.com/jenkinsci/openstack-cloud-plugin/commit/cf6e1d0e2cff2b6695f2059b58a486d6284b030d)
    [#254](https://github.com/jenkinsci/openstack-cloud-plugin/issues/254)[: Improve error reporting
    during manual
    provisioning](https://github.com/jenkinsci/openstack-cloud-plugin/commit/cf6e1d0e2cff2b6695f2059b58a486d6284b030d)
-   [Do not count connection time into idle
    time](https://github.com/jenkinsci/openstack-cloud-plugin/pull/258)

##### Version 2.46.1

**Jenkins Configuration as Code (JCasC) is not supported for older
releases.**

-   Correctly handle username in Configuration as Code (JCasC)

##### Version 2.46

-   Configuration as Code (JCasC) support introduced

##### Version 2.45

-   Show correct time in "Pending deletion" messages
-   Rework handling of `retentionTime == 0` scenarios

##### Version 2.44

-   Prevent nodes to be deleted while provisioning where
    `retentionTime == 0` 
-   Warn users JNLP secret in cloud-stats is inherently insecure
-   Consult Jenkins computer count for maintaining maximal instance
    capacity
-   Stop tracking servers to delete when cloud is deleted or renamed

##### Version 2.43

-   Use upstream/unpatched openstack4j again
-   Use 20 seconds socket timeout when taking to OpenStack
-   Invalidate OpenStack client instance when password changes
-   Add support for SSH launching over Ipv6

##### Version 2.42

-   [Make sure the user id is preserved for manually provisioned
    nodes](https://github.com/jenkinsci/openstack-cloud-plugin/commit/b712cc957553f1491284ef37b05ac0c9697c896b)
-   [Fix manual provisioning with multiple clouds
    configured](https://github.com/jenkinsci/openstack-cloud-plugin/commit/b153b5c67767d3d03cf68603b29c3c73e08f91ef)

##### Version 2.41

-   [Prevent provisioning failure when VolumeSnapshot has no
    description.](https://github.com/jenkinsci/openstack-cloud-plugin/commit/3a08f2ebb4ca8c1f5d86ba8b99dafa52f510a5d9)
-   [Mark a node used only after a task has
    complete](https://github.com/jenkinsci/openstack-cloud-plugin/commit/9c069efd0375cfdf20b9905ceb378ee30361ec56)
    -   Further fixes ware delivered for use-case where
        `retentionTime == 0` in later versions

##### Version 2.40

-   [Switch to OpenStack Glance v2 -
    reworked](https://github.com/jenkinsci/openstack-cloud-plugin/pull/218)

##### Version 2.39

-   Revert glance v2 switch to avoid the regression from 2.38

##### Version 2.38

-   Switched to OpenStack Glance v2 breaking deployments with more than
    25 images - use 2.40 or newer  
-   Use human readable description for server flavor sizes
-   [Do not wait for provisioning to complete in http handling
    thread](https://github.com/jenkinsci/openstack-cloud-plugin/issues/180)
-   Make sure slave with 0 retention time will not be reused
-   [Interrupt matrix parents when killing computer,
    too](https://github.com/jenkinsci/openstack-cloud-plugin/pull/213)
-   [Relax template/cloud name
    restrictions](https://github.com/jenkinsci/openstack-cloud-plugin/pull/214)  
      

##### Version 2.37

-   Connection leak regression originally fixed in 2.27 - use 2.38 or
    newer
-   [Fix problems introduced by security patch in
    2.36](https://github.com/jenkinsci/openstack-cloud-plugin/issues/208)

##### Version 2.36

-   [Fix security
    issue](https://jenkins.io/security/advisory/2018-06-25/#SECURITY-808)

##### Version 2.35

-   Add support for enforcing minimal number of nodes running per
    template

##### Version 2.34

-   Add support for provisioning servers in multiple networks
-   Avoid chatty logging for periodic work

##### Version 2.33

-   This plugin requires Java 8 from this release on
-   [Improve diagnostics in case of failed agent
    launch](https://github.com/jenkinsci/openstack-cloud-plugin/commit/32fd027ab2826834818f39885f13958678935c1e)
-   [Improved Jenkins orphanned computer
    detection](https://github.com/jenkinsci/openstack-cloud-plugin/commit/f04e333ae4809529e47e05d5ce0ccdeb2ff9ad86)

##### Version 2.32

-   [Support booting to
    volume](https://github.com/jenkinsci/openstack-cloud-plugin/pull/194)
-   [Rework computer termination to prevent repeated
    Channel#close](https://github.com/jenkinsci/openstack-cloud-plugin/commit/797aec00df9a1a1f93fd32fa2f3302da435265e3)
-   Note this does not contain the fix for
    [ContainX/openstack4j#1151](https://github.com/ContainX/openstack4j/pull/1151)

##### Version 2.31.1

-   Add hotfix for
    [ContainX/openstack4j#1151](https://github.com/ContainX/openstack4j/pull/1151)

##### Version 2.31

-   [Ensure node names never
    collide](https://github.com/jenkinsci/openstack-cloud-plugin/commit/88ecbd2f9354a8640d3db683ebdfb977524c3103)
-   [OpenStackj4 Neutron Networks object
    incompatible](https://github.com/jenkinsci/openstack-cloud-plugin/issues/189)

##### Version 2.30

-   [Add support for project
    domains](https://github.com/jenkinsci/openstack-cloud-plugin/pull/165)
-   [Add ability to skip ssl
    check](https://github.com/jenkinsci/openstack-cloud-plugin/pull/184)
-   [Report computer "fatal" offline cause when present when destroying
    computer](https://github.com/jenkinsci/openstack-cloud-plugin/commit/255f8beb5bda7af6576e47f0e76679079ef6a2ed)

##### Version 2.29 (2017-10-20)

-   [Fix regression in region
    handling](https://github.com/jenkinsci/openstack-cloud-plugin/issues/178)
-   [Do not report failed FIP deletion in cloud statistics if failed
    with
    404](https://github.com/jenkinsci/openstack-cloud-plugin/commit/0944ad64aad8888034236dcb5a265ab714aa4363 "Do not report failed FIP deletion if failed with 404")
-   [Add support for
    volumeSnapshots](https://github.com/jenkinsci/openstack-cloud-plugin/pull/136)
-   [Do not use expired login
    sessions](https://github.com/jenkinsci/openstack-cloud-plugin/pull/177)

##### Version 2.27 (2017-10-03)

-   `Improve reporting of boot timeout`
-   `Abort provisioning/launching when server gets deleted`
-   `Add support for explicit java path when SSHLauncher is used`
-   `Bump openstack4j okhttp connector to avoid occasional connection leaks`
-   `Avoid phony cloud-stats warnings logged`

##### Version 2.26

*Botched release - changes went to 2.27*

##### Version 2.25 (2017-09-25)

-   `Fix #168: Prevent tracking disposal of the same server several times`
-   `Use ok-http to prevent connection blockage (prefer 2.27 with followup fix)`
-   `Fix #167: Specify node readiness timeout cause`

-   Refactor slave type into describable

##### Version 2.24 (2017-08-17)

**Note this version is affected by httpclient connection leak - use 2.27 instead.**

-   Pipeline step for agentless node provisioning

-   Prefer IPv4 address for SSH launcher
-   Prevent occasional IllegalArgumentException: Failed to instantiate class jenkins.plugins.openstack.compute.SlaveOptions while saving global configuration page

-   First attempt to implement Openstack client caching between requests
-   [This version is affected by occasional httpclient connection
    blockage](https://github.com/jenkinsci/openstack-cloud-plugin/issues/171)

##### Version 2.23

-   Issue
    [#128](https://github.com/jenkinsci/openstack-cloud-plugin/issues/128): Do not fail when FIP
    service is disallowed (403) on paths that does not require floating
    IP (introduced in 2.21)

-   [This version is affected by occasional httpclient connection
    blockage](https://github.com/jenkinsci/openstack-cloud-plugin/issues/171)

##### Version 2.22

-   Investigate `SSH channel is closed`/`No route to host` ([Issue
    #149](https://github.com/jenkinsci/openstack-cloud-plugin/issues/149))

##### Version 2.21

-   `Fix #148: Skip unknown variables in user data`
-   `Record manual provisioning attempt failure for unexpected exceptions`
-   Issue #128: Request: Allow VMs without floating IPs ([followup fix
    from 2.23
    needed](https://github.com/jenkinsci/openstack-cloud-plugin/issues/128#issuecomment-293425245))

##### Version 2.20

*No user visible changes included*

##### Version 2.19

-   `Fixed #84: Destroy leaked floating IPs`

-   `Fix #137: Retry when ssh launcher fail to bring the node online silently`

-   `Fix #144: Bring the node sidebar links that ware removed accidentally`
-   `Fix #109: Generate documentation for variables replaced in user data`

-   `Fix waiting for JNLP agents` ` (JENKINS-42207`)
-   `Do not discard nodes that are being provisioned`

##### Version 2.18

-   Discard old nodes asynchronously
-   Restore compatibility with config-file-provider 2.14+
-   Collect leaked OpenStack servers and Jenkins slaves once they do not
    have the counterpart
-   `Issue #140: Report meaningful issue in case instance boot times out`

##### Version 2.17

-   Restore config-file-provider <2.13 support properly Jenkins

##### Version 2.16

-   Restore config-file-provider <2.13 support - *Do not use this
    version!*

##### Version 2.15 (2017-01-02)

-   Do not wait for successful launch while provisioning.
    -   There should be less failed launch attempts right after the node
        is provisioned.
        -   The time statistics are not comparable to the older ones
            (provisioning time is longer, launching is shorter).

##### Version 2.14 (2016-11-21)

-   Bugfix; do not fail when region is empty.

##### Version 2.13 (2016-10-22)

-   Bugfix: avoid classloading issue caused by pom refactoring.

##### Version 2.8 (2016-06-06)

-   Fix floating IP deallocation when machine is deleted ([Issue #81](https://github.com/jenkinsci/openstack-cloud-plugin/issues/81))

##### Version 2.7 (2016-05-16)

-   Do not leak servers when floating ip assignment fails.
-   Avoid deadlock caused by adding and deleting OpenStack nodes.
-   Avoid phony failures in `destroyServer` cause by server disappearing
    when retrying deletion.

##### Version 2.6 (2016-05-10)

-   Integrate .

##### Version 2.5 (2016-05-05)

-   Make sure plugin can reach all important endpoints when testing
    connection
    ([JENKINS-34578](https://issues.jenkins-ci.org/browse/JENKINS-34578))

##### Version 2.4 (2016-05-04)

-   Plugin fails to resolve image ID on some OpenStack deployments
    ([JENKINS-34495](https://issues.jenkins-ci.org/browse/JENKINS-34495))

##### Version 2.3 (2016-04-21)

-   Never remove slave put temporarily offline by user
-   Plugin can now handle images with blank name
-   Fix server deletion retry logic
-   Make key-pair field selectable on global config page

##### Version 2.2 (2016-04-11)

-   OpenStack slaves can be put into "pending delete" state pressing
    "Delete" button while build is in progress.
-   Instances out of disk space in /tmp or workspace will be put into
    "pending delete" state and removed eventually.
-   Maximal number of instances limitation implemented for templates.
-   Maximal number of instances can be set to more than 10 (regression
    from 2.1).

##### Version 2.1 (2016-03-31)

-   Machine/slave options can be specified on both cloud level as well
    as template level (Maximal number of instances limitation is
    implemented on 2.2).
-   Images/snapshots are identified by name, not image id.
-   Add support for floating pool name selection.

##### Version 2.0 (2016-02-22)

-   Jobs without label are never scheduled, so does most of matrix
    combinations([JENKINS-29998](https://issues.jenkins-ci.org/browse/JENKINS-29998))
-   Drop support for blobstore. (This is not a rejection of the feature.
    None of the maintainers have an environment to reproduce this.
    Please reach us if you care for this feature and have an option to
    run the tests)
-   Drop support for injecting private key from plugin. Should be done
    by configuration management.
-   Replace JClouds backend with openstack4j.
-   Move to singlemodule maven project avoiding dependency shading.

##### **Version 1.5 (released February 2015)**

-   UserData scripts now managed
    by [Config-File-Provider](https://wiki.jenkins-ci.org/display/JENKINS/Config+File+Provider+Plugin) plugin

##### Version 1.4 (released February 2015)

-   InitScript is moved out. use cloud-init plus userData instead
-   Fix bug with multiple zones, now plugin restricts user to only one
    single zone
-   get rid of SpoolingBeforeInstanceCreation as it is paid-cloud
    parameter only

##### Version 1.3 (released January, 2015)

-   Initial release

 

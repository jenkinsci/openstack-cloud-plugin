# Changelog  
## Version 2.32  
  
- Support booting to volume  
- Rework computer termination to prevent repeated Channel\#close  
  
## Version 2.31  
  
- Ensure node names never collide  
- OpenStackj4 Neutron Networks object incompatible  
  
## Version 2.30  
  
- Add support for project domains  
- Add ability to skip ssl check  
- Report computer "fatal" offline cause when present when destroying
computer  
  
## Version 2.29 (2017-10-20)  
  
- Fix regression in region handling  
- Do not report failed FIP deletion in cloud statistics if failed with
404  
- Add support for volumeSnapshots  
- Do not use expired login sessions  
  
## Version 2.27 (2017-10-03)  
  
- Improve reporting of boot timeout  
- Abort provisioning/launching when server gets deleted  
- Add support for explicit java path when SSHLauncher is used  
- Bump openstack4j okhttp connector to avoid occasional connection
leaks  
- Avoid phony cloud-stats warnings logged  
  
## Version 2.26  
  
Botched release - changes went to 2.27  
## Version 2.25 (2017-09-25)  
  
- Fix #168: Prevent tracking disposal of the same server several
times  
- Use ok-http to prevent connection blockage (prefer 2.27 with
followup fix)  
- Fix #167: Specify node readiness timeout cause  
  
- Refactor slave type into describable  
  
## Version 2.24 (2017-08-17)  
  
**Note this version is affected by httpclient connection leak - use 2.27
instead.**
  
- Pipeline step for agentless node provisioning  
- Prefer IPv4 address for SSH launcher  
  
- Prevent occasional IllegalArgumentException: Failed to instantiate
class jenkins.plugins.openstack.compute.SlaveOptions while saving global
configuration page  
  
- First attempt to implement Openstack client caching between
requests  
- This version is affected by occasional httpclient connection
blockage  
  
## Version 2.23  
  
- Issue #128: Do not fail when FIP service is disallowed (403) on
paths that does not require floating IP (introduced in 2.21)  
- This version is affected by occasional httpclient connection
blockage  
  
## Version 2.22  
  
- Investigate SSH channel is closed/No route to host (Issue #149)  
  
## Version 2.21  
  
- Fix #148: Skip unknown variables in user data  
- Record manual provisioning attempt failure for unexpected
exceptions  
  
- Issue #128: Request: Allow VMs without floating IPs (followup fix
from 2.23 needed)  
  
## Version 2.20  
  
No user visible changes included  
## Version 2.19  
  
- Fixed #84: Destroy leaked floating IPs  
- Fix #137: Retry when ssh launcher fail to bring the node online
silently  
- Fix #144: Bring the node sidebar links that ware removed
accidentally  
- Fix #109: Generate documentation for variables replaced in user
data  
- Fix waiting for JNLP agents  (JENKINS-42207)  
- Do not discard nodes that are being provisioned
  
## Version 2.18  
  
- Discard old nodes asynchronously  
- Restore compatibility with config-file-provider 2.14+  
- Collect leaked OpenStack servers and Jenkins slaves once they do not
have the counterpart  
- Issue #140: Report meaningful issue in case instance boot times
out  
  
## Version 2.17  
  
- Restore config-file-provider <2.13 support properly  
  
## Version 2.16  
  
- Restore config-file-provider <2.13 support - **Do not use this version!**
  
## Version 2.15 (2017-01-02)  
  
- Do not wait for successful launch while provisioning.  
    - There should be less failed launch attempts right after the node
is provisioned.  
    - The time statistics are not comparable to the older ones (provisioning time is longer, launching is shorter).  
  
## Version 2.14 (2016-11-21)  
  
- Bugfix; do not fail when region is empty.  
  
## Version 2.13 (2016-10-22)  
  
- Bugfix: avoid classloading issue caused by pom refactoring.  
  
## Version 2.8 (2016-06-06)  
  
- Fix floating IP deallocation when machine is deleted (Issue #81)  
  
## Version 2.7 (2016-05-16)  
  
- Do not leak servers when floating ip assignment fails.  
- Avoid deadlock caused by adding and deleting OpenStack nodes.  
- Avoid phony failures in destroyServer cause by server disappearing
when retrying deletion.  
  
## Version 2.6 (2016-05-10)  
  
- Integrate Cloud Statistics Plugin.  
  
## Version 2.5 (2016-05-05)  
  
- Make sure plugin can reach all important endpoints when testing
connection (JENKINS-34578)  
  
## Version 2.4 (2016-05-04)  
  
- Plugin fails to resolve image ID on some OpenStack deployments
(JENKINS-34495)  
  
## Version 2.3 (2016-04-21)  
  
- Never remove slave put temporarily offline by user  
- Plugin can now handle images with blank name  
- Fix server deletion retry logic  
- Make key-pair field selectable on global config page  
  
## Version 2.2 (2016-04-11)  
  
- OpenStack slaves can be put into "pending delete" state pressing
"Delete" button while build is in progress.  
- Instances out of disk space in /tmp or workspace will be put into
"pending delete" state and removed eventually.  
- Maximal number of instances limitation implemented for templates.  
- Maximal number of instances can be set to more than 10 (regression
from 2.1).  
  
## Version 2.1 (2016-03-31)  
  
- Machine/slave options can be specified on both cloud level as well
as template level (Maximal number of instances limitation is implemented
on 2.2).  
- Images/snapshots are identified by name, not image id.  
- Add support for floating pool name selection.  
  
## Version 2.0 (2016-02-22)  
  
- Jobs without label are never scheduled, so does most of matrix
combinations(JENKINS-29998)  
- Drop support for blobstore. (This is not a rejection of the feature.
None of the maintainers have an environment to reproduce this. Please
reach us if you care for this feature and have an option to run the
tests)  
- Drop support for injecting private key from plugin. Should be done
by configuration management.  
- Replace JClouds backend with openstack4j.  
- Move to singlemodule maven project avoiding dependency shading.  
  
## Version 1.5 (released February 2015)  
  
- UserData scripts now managed by Config-File-Provider plugin  
  
## Version 1.4 (released February 2015)  
  
- InitScript is moved out. use cloud-init plus userData instead  
- Fix bug with multiple zones, now plugin restricts user to only one
single zone  
- get rid of SpoolingBeforeInstanceCreation as it is paid-cloud
parameter only  
  
## Version 1.3 (released January, 2015)  
  
- Initial release

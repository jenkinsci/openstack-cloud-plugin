Behaviour.specify("[data-type='os-provision']", 'os-provision', -99, function(e) {
e.addEventListener("click", function (event) {
    var notification = document.getElementById("os-notifications")
      fetch(e.dataset.cloud, {
        method: "POST",
        headers: crumb.wrap({}),
        body: new URLSearchParams({ name: e.dataset.url })
      }).then((rsp) => {
        if (!rsp.ok) {
          rsp.text().then((responseText) => {
            alert('Provisioning failed: ' + responseText)
            console.log('Provisioning failed: ' + rsp.status + " " + rsp.statusText + ": " + responseText)
          })
        } else {
          hoverNotification('Provisioning started', notification);
        }
      });
    });
});

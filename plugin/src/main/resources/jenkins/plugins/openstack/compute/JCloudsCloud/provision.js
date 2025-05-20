Behaviour.specify("[data-type='os-provision']", 'os-provision', -99, function(e) {
  e.addEventListener("click", function (event) {
    const form = document.getElementById(e.dataset.form);
    form.querySelector("[name='template']").value = e.dataset.url;
    buildFormTree(form);
    form.submit();
  });
});

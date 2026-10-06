/** Oracle JET AMD runtime with Knockout owning form values. */
let koRuntime;
export const jetReady = new Promise((resolve, reject) => {
  window.require.config({
    baseUrl: "/",
    paths: {
      ojs: "/vendor/jet/debug",
      ojL10n: "/vendor/jet/ojL10n",
      ojtranslations: "/vendor/jet/resources",
      knockout: "/vendor/knockout/knockout-latest",
      jquery: "/vendor/jquery/jquery",
      "jqueryui-amd": "/vendor/jquery-ui",
      hammerjs: "/vendor/hammer/hammer",
      signals: "/vendor/signals/signals",
      preact: "/vendor/preact/preact.umd",
      "preact/compat": "/vendor/preact-compat/compat.umd",
      "preact/hooks": "/vendor/preact-hooks/hooks.umd",
      "preact/jsx-runtime": "/vendor/preact-jsx/jsxRuntime.umd",
      "@oracle/oraclejet-preact": "/vendor/jet-preact",
      text: "/vendor/text/text",
      css: "/vendor/css/css",
    },
    config: { ojL10n: { locale: "en" } },
  });
  window.require(
    [
      "knockout",
      "ojs/ojbootstrap",
      "ojs/ojarraydataprovider",
      "ojs/ojcontext",
      "ojs/ojknockout",
      "ojs/ojinputtext",
      "ojs/ojselectsingle",
      "ojs/ojdatetimepicker",
    ],
    (ko, Bootstrap, ArrayDataProvider, Context) =>
      Bootstrap.whenDocumentReady().then(() => {
        koRuntime = ko;
        resolve({ ko, ArrayDataProvider, Context });
      }),
    reject,
  );
});

/** Enhances legacy and new forms alike; only Knockout writes the hidden submitted value. */
export async function enhanceForms(root) {
  const { ko, ArrayDataProvider } = await jetReady;
  for (const input of root.querySelectorAll(
    "input:not([data-jet-source]),select:not([data-jet-source]),textarea:not([data-jet-source])",
  )) {
    // Revalidation must not wrap the native inputs generated inside JET components.
    if (input.closest('.jet-field')) continue;
    if (["hidden", "checkbox", "radio", "file"].includes(input.type)) continue;
    const label = input.closest("label.field");
    if (!label) continue;
    const text =
      Array.from(label.childNodes)
        .find((n) => n.nodeType === Node.TEXT_NODE)
        ?.textContent?.trim() || input.name;
    const type = input.type;
    const value = ko.observable(input.value || null);
    const shell = document.createElement("div");
    shell.className = "jet-field";
    const component = document.createElement(
      input.tagName === "SELECT"
        ? "oj-select-single"
        : input.tagName === "TEXTAREA"
          ? "oj-text-area"
          : type === "password"
            ? "oj-input-password"
            : type === "date"
              ? "oj-input-date"
              : "oj-input-text",
    );
    component.setAttribute("label-hint", text);
    component.setAttribute("label-edge", "inside");
    component.value = value();
    component.required = input.required;
    component.disabled = input.matches(":disabled");
    if (input.placeholder) component.placeholder = input.placeholder;
    if (input.tagName === "SELECT") {
      const items = Array.from(input.options).map((o) => ({
        value: o.value,
        label: o.textContent,
      }));
      component.data = new ArrayDataProvider(items, { keyAttributes: "value" });
      component.itemText = "label";
    }
    for (const node of Array.from(label.childNodes))
      if (node.nodeType === Node.TEXT_NODE) node.textContent = "";
    input.dataset.jetSource = "true";
    input.hidden = true;
    input.required = false;
    label.append(shell);
    shell.append(component);
    ko.applyBindings({ value }, shell);
    const subscription = value.subscribe((v) => {
      input.value = v ?? "";
      if (component.value !== v) component.value = v;
      input.dispatchEvent(new Event("change", { bubbles: true }));
    });
    component.addEventListener("valueChanged", (event) =>
      value(event.detail.value),
    );
    ko.utils.domNodeDisposal.addDisposeCallback(shell, () =>
      subscription.dispose(),
    );
    input.addEventListener("change", () => {
      if (value() !== input.value) value(input.value || null);
    });
    label.dataset.jetLabel = "true";
  }
}

/** Browser forms await component validation instead of relying on hidden native input constraints. */
export async function validateJet(root) {
  await enhanceForms(root);
  let valid = true;
  for (const input of root.querySelectorAll(
    "oj-input-text,oj-input-password,oj-input-date,oj-text-area,oj-select-single",
  ))
    if (!input.disabled && (await input.validate()) !== "valid") valid = false;
  return valid;
}
/** Disposes Knockout subscriptions before replacing a rendered view. */
export async function disposeJet(root) {
  if (koRuntime) {
    if (root) koRuntime.cleanNode(root);
    return;
  }
  const { ko } = await jetReady;
  if (root) ko.cleanNode(root);
}

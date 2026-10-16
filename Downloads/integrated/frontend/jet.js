/** Oracle JET AMD runtime with Knockout owning form values. */
import {locale} from './i18n.js';
let koRuntime;
/** Preserve native constraints when the submitted source is replaced by a JET control. */
export function constraintValidator({type,pattern,min,max,step,minLength,maxLength},label='Value') {
  return {validate(value) {
    if(value===null||value===undefined||value==='')return;
    const text=String(value);
    const fail=message=>{throw new Error(label+' '+message);};
    if(minLength!==null&&minLength!==undefined&&text.length<Number(minLength))fail('is too short.');
    if(maxLength!==null&&maxLength!==undefined&&text.length>Number(maxLength))fail('is too long.');
    if(pattern&&!new RegExp('^(?:'+pattern+')$','u').test(text))fail('has an invalid format.');
    if(type==='email'&&!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(text))fail('must be a valid email address.');
    if(type==='number') {
      const number=Number(text);if(!Number.isFinite(number))fail('must be a number.');
      if(min!==null&&min!==undefined&&number<Number(min))fail('must be at least '+min+'.');
      if(max!==null&&max!==undefined&&number>Number(max))fail('must be at most '+max+'.');
      const increment=step==='any'?null:Number(step||1),base=Number(min??0);
      if(increment>0&&Math.abs((number-base)/increment-Math.round((number-base)/increment))>1e-8)fail('must use increments of '+increment+'.');
    }
  }};
}
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
    config: { ojL10n: { locale } },
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
    // JET renders select options outside a native modal's top layer.
    // Native selects remain usable inside dialogs and submit their value directly.
    if (input.tagName === "SELECT" && input.closest("dialog")) continue;
    if (["hidden", "checkbox", "radio", "file"].includes(input.type)) continue;
    // A JET select popup cannot rise above a native modal dialog. Keep modal
    // selects and date inputs native so their option lists and pickers open.
    if (input.closest("dialog") &&
        (input.tagName === "SELECT" || ["date", "datetime-local"].includes(input.type))) continue;
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
    component.userAssistanceDensity = "compact";
    component.value = value();
    component.required = input.required;
    component.disabled = input.matches(":disabled");
    if (input.tagName !== "SELECT") component.readonly = input.readOnly;
    if (input.placeholder) component.placeholder = input.placeholder;
    if(input.tagName!=="SELECT")component.validators=[constraintValidator({type,
      pattern:input.getAttribute('pattern'),min:input.getAttribute('min'),max:input.getAttribute('max'),
      step:input.getAttribute('step'),minLength:input.getAttribute('minlength'),maxLength:input.getAttribute('maxlength')},text)];
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
    for(const attribute of ['pattern','min','max','step','minlength','maxlength'])input.removeAttribute(attribute);
    if(input.tagName==='INPUT')input.type='hidden';
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
export function syncConditionalField(input, {required, disabled, clear = false}) {
  const component = input.closest('label.field')?.querySelector(
    'oj-input-text,oj-input-password,oj-input-date,oj-text-area,oj-select-single',
  );
  input.disabled = disabled;
  input.required = input.dataset.jetSource ? false : required;
  if (component) {
    component.required = required;
    component.disabled = disabled;
  }
  if (clear) {
    input.value = '';
    if (component) component.value = null;
    input.dispatchEvent(new Event('change', {bubbles: true}));
  }
}

export async function validateJet(root) {
  await enhanceForms(root);
  let valid = true;
  for (const input of root.querySelectorAll(
    "oj-input-text,oj-input-password,oj-input-date,oj-text-area,oj-select-single",
  ))
    if (!input.disabled && (await input.validate()) !== "valid") valid = false;
  for(const input of root.querySelectorAll('input:not([data-jet-source]),select:not([data-jet-source]),textarea:not([data-jet-source])'))
    if(!input.closest('.jet-field')&&!input.disabled&&!input.reportValidity())valid=false;
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

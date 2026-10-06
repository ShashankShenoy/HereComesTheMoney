import { api, download, getSession } from "./api.js";
import {
  esc,
  field,
  select,
  reason,
  table,
  badge,
  date,
  dialog,
  toast,
} from "./ui.js";
import { formatAmount, formatDate, fieldKind, rowCurrency } from "./presentation.js";

// Domain screens own their selection state; shared authentication remains in api.js.
const state = {
  customer: null,
  case: null,
  product: null,
  version: null,
  branch: "",
  search: "",
  schema: [],
  customerDetail: null,
  caseDetail: null,
  productDetail: null,
  versionDetail: null,
  customerTab: "profile",
  productTab: "overview",
  versionTab: "overview",
};
let repaint = async () => {};
let selectedUser;
/** Clears per-user view selections when a different identity signs in. */
function syncUser() {
  const id = getSession()?.user.userId;
  if (id === selectedUser) return;
  selectedUser = id;
  Object.assign(state, {
    customer: null,
    case: null,
    product: null,
    version: null,
    branch: "",
    search: "",
    customerDetail: null,
    caseDetail: null,
    productDetail: null,
    versionDetail: null,
    customerTab: "profile",
    productTab: "overview",
    versionTab: "overview",
  });
}
const can = (permission) => getSession()?.user.permissions.includes(permission);
const primaryActions = new Set(["customer-new", "product-new", "version-new", "case-new"]);
const button = (label, action, id = "", extra = "") =>
  `<button class="btn small${primaryActions.has(action) ? " primary" : ""}" data-domain-action="${action}" data-id="${esc(id)}" ${extra}>${esc(label)}</button>`;
const panel = (name, body, tools = "") =>
  `<section class="panel"><div class="panel-heading"><h3>${esc(name)}</h3>${tools}</div>${body}</section>`;
const heading = (name, module, sub, tools = "") =>
  `<div class="page-heading"><div><p class="eyebrow">${module === "02" ? "CUSTOMER INFORMATION" : "PRODUCT MANAGEMENT"}</p><h1>${esc(name)}</h1><p>${esc(sub)}</p></div>${tools}</div>`;
const grid = (body) => `<div class="grid2">${body}</div>`;
const fieldLabels = {HOME_BRANCH_REF:"Home branch",SEGMENT_CODE:"Segment",RISK_LEVEL:"Risk level",CIF_NUMBER:"CIF number",KYC_STATUS:"KYC status",CURRENCY_CODE:"Currency",VERSION_NO:"Version",VERSION_STATE:"Status",DEFAULT_TXN_ACTION:"Default transaction action",PRODUCT_CODE:"Product code",PRODUCT_TYPE:"Product type",BUSINESS_OWNER_REF:"Business owner",EFFECTIVE_FROM_AT:"Effective from",EFFECTIVE_TO_AT:"Effective to",SALES_START_AT:"Sales start",CREATED_AT:"Created"};
const label = (value) => fieldLabels[value] || String(value)
  .toLowerCase().replaceAll("_", " ").replace(/\b\w/g, (c) => c.toUpperCase())
  .replace(/\b(Cif|Kyc|Gl|Fx|Id|Pdf|Url)\b/g, word => word.toUpperCase());
const detailPriority = ["LEGAL_NAME","CIF_NUMBER","PRODUCT_NAME","PRODUCT_CODE","VERSION_NO","STATUS","VERSION_STATE","KYC_STATUS","HOME_BRANCH_REF","SEGMENT_CODE","RISK_LEVEL","PRODUCT_TYPE","CURRENCY_CODE","BUSINESS_OWNER_REF","DEFAULT_TXN_ACTION","EFFECTIVE_FROM_AT","EFFECTIVE_TO_AT","SALES_START_AT","CREATED_AT"];
const details = (row) => {
  const entries = Object.entries(row).filter(([key]) => !/(HASH|SESSION|PASSWORD|SECRET|TOKEN)/i.test(key));
  const prioritized = detailPriority.flatMap(key => entries.filter(([name]) => name === key)).slice(0,8);
  const primary = prioritized.length ? prioritized : entries.slice(0,8);
  const remaining = entries.filter(entry => !primary.includes(entry));
  const render = ([key, value]) => {
    const kind = fieldKind(key);
    const formatted = kind === "amount" ? formatAmount(value, rowCurrency(row, key)) : kind === "date" ? formatDate(value) : value ?? "—";
    return `<div><dt>${esc(label(key))}</dt><dd>${kind === "status" && value != null ? badge(value) : esc(formatted)}</dd></div>`;
  };
  return `<dl class="details">${primary.map(render).join("")}</dl>` +
    (remaining.length ? `<details class="record-metadata"><summary>Additional record fields</summary><dl class="details">${remaining.map(render).join("")}</dl></details>` : "");
};
const iso = (v) => (v ? new Date(v).toISOString() : null);
const local = (v) => {
  const d = new Date(v || Date.now());
  return new Date(d.getTime() - d.getTimezoneOffset() * 60000)
    .toISOString()
    .slice(0, 16);
};
const query = () =>
  state.branch ? `?branch=${encodeURIComponent(state.branch)}` : "";
const send = (path, body, method = "POST") => api(path, { method, body });
const object = (data) =>
  Object.fromEntries(
    Array.from(data).map(([k, v]) => [k, v === "" ? null : v]),
  );
function organizePanels(html, scope, groups) {
  const host = document.createElement("div");
  host.innerHTML = html;
  const panels = [...host.children].filter(node => node.matches("section.panel"));
  if (panels.length < 2) return html;
  const current = groups.some(([id]) => id === state[`${scope}Tab`]) ? state[`${scope}Tab`] : groups[0][0];
  const tabs = document.createElement("div");
  tabs.className = "record-tabs";
  tabs.setAttribute("role", "tablist");
  tabs.setAttribute("aria-label", `${scope} sections`);
  for (const [id, title] of groups) {
    const button = document.createElement("button");
    button.type = "button";
    button.setAttribute("role", "tab");
    button.dataset.domainTab = `${scope}:${id}`;
    button.setAttribute("aria-selected", String(id === current));
    button.textContent = title;
    tabs.append(button);
  }
  host.querySelector(".page-heading")?.after(tabs);
  const sections = new Map(groups.map(([id]) => {
    const section = document.createElement("div");
    section.className = "record-tab-panel";
    section.dataset.domainPanel = `${scope}:${id}`;
    section.hidden = id !== current;
    host.append(section);
    return [id, section];
  }));
  for (const panel of panels) {
    const title = panel.querySelector(".panel-heading h3")?.textContent || "";
    const match = groups.find(([, , names]) => names.some(name => title === name || title.startsWith(name + " · ") || title.startsWith(name + " ")));
    sections.get(match?.[0] || (scope === "version" ? groups.at(-1)[0] : groups[0][0])).append(panel);
  }
  const available = [...sections].filter(([, section]) => section.children.length).map(([id]) => id);
  const visible = available.includes(current) ? current : available[0];
  state[`${scope}Tab`] = visible;
  for (const [id, section] of sections) {
    const button = tabs.querySelector(`[data-domain-tab="${scope}:${id}"]`);
    if (!available.includes(id)) { button.remove(); section.remove(); continue; }
    section.hidden = id !== visible;
    button.setAttribute("aria-selected", String(id === visible));
  }
  return host.innerHTML;
}
/** Connects route repainting without coupling the domain modules to the IAM module's internals. */
export function configureDomains(render) {
  repaint = render;
}

/** Staff directory and linked-customer profile use the same resource-authorized API. */
export async function customersScreen() {
  syncUser();
  if (state.customer) return customerView();
  const rows = await api(
    `/cif/customers?search=${encodeURIComponent(state.search)}`,
  );
  return (
    heading(
      "Customers & KYC",
      "02",
      "Customer profiles, evidence and independent verification.",
      can("CIF_CREATE") ? button("Create customer", "customer-new") : "",
    ) +
    panel(
      "Customer directory",
      `<form id="customer-search" class="toolbar">${field("Search name or CIF", "search", "search", { required: false, value: state.search })}<button class="btn">Search</button></form>` +
        table(
          ["Customer", "CIF", "Branch", "Segment", "Status", "KYC", "Action"],
          rows.map((c) => [
            `<strong>${esc(c.LEGAL_NAME)}</strong>`,
            esc(c.CIF_NUMBER),
            esc(c.HOME_BRANCH_REF),
            esc(c.SEGMENT_CODE),
            badge(c.STATUS),
            badge(c.KYC_STATUS),
            button("View customer", "customer-open", c.CIF_ID),
          ]),
        ),
      can("KYC_REVIEW") ? button("Expire due reviews", "expire-reviews") : "",
    )
  );
}

/** History is visible alongside pending revisions; masked contacts/identifiers stay masked. */
async function customerView() {
  const d = await api(`/cif/customers/${state.customer}`);
  state.customerDetail = d;
  const c = d.customer;
  const displayName = d.names.find(name => name.NAME_TYPE === "LEGAL" && !name.VALID_TO)?.FULL_NAME || c.CIF_NUMBER;
  let html =
    heading(
      displayName,
      "02",
      `CIF ${c.CIF_NUMBER} · ${c.STATUS} · KYC ${c.KYC_STATUS} · ${c.HOME_BRANCH_REF}`,
      button("Back to directory", "customer-back"),
    ) +
    panel(
      "Customer profile",
      `<div class="panel-body">${details({ ...c, ...d.party })}</div>`,
      can("CIF_UPDATE")
        ? `${button("Propose profile change", "profile-edit")}${button("Change status", "customer-status")}`
        : "",
    ) +
    panel(
      "Names & history",
      table(
        ["Name", "Type", "Verification", "Validity"],
        d.names.map((n) => [
          esc(n.FULL_NAME),
          esc(n.NAME_TYPE),
          badge(n.VERIFICATION_STATUS),
          `${esc(date(n.VALID_FROM))}<span class="sub">${esc(date(n.VALID_TO))}</span>`,
        ]),
      ),
    ) +
    panel(
      "Contacts",
      table(
        ["Type", "Masked value", "Verification", "Validity"],
        d.contacts.map((c) => [
          esc(c.CONTACT_TYPE),
          esc(c.DISPLAY_HINT),
          badge(c.VERIFICATION_STATUS),
          `${esc(date(c.VALID_FROM))}<span class="sub">${esc(date(c.VALID_TO))}</span>`,
        ]),
      ),
      can("CIF_UPDATE") ? button("Add contact", "contact-new") : "",
    ) +
    panel(
      "Identifiers",
      table(
        ["Type", "Masked value / issuer", "Verification", "Expiry"],
        d.identifiers.map((i) => [
          esc(i.IDENTIFIER_TYPE),
          `${esc(i.DISPLAY_HINT)}<span class="sub">${esc(i.ISSUER_CODE)}</span>`,
          badge(i.VERIFICATION_STATUS),
          esc(i.EXPIRES_ON),
        ]),
      ),
      can("CIF_UPDATE") ? button("Add identifier", "identifier-new") : "",
    ) +
    panel(
      "Addresses",
      table(
        ["Address", "Type", "Verification", "Evidence"],
        d.addresses.map((a) => [
          `${esc(a.LINE1)}<span class="sub">${esc(a.CITY)}, ${esc(a.COUNTRY_CODE)} ${esc(a.POSTAL_CODE)}</span>`,
          esc(a.ADDRESS_TYPE),
          badge(a.VERIFICATION_STATUS),
          esc(a.EVIDENCE_DOCUMENT_ID),
        ]),
      ),
      can("CIF_UPDATE") ? button("Add address", "address-new") : "",
    ) +
    panel(
      "KYC cases",
      table(
        ["Case", "Status", "Maker", "Review due", ""],
        d.cases.map((k) => [
          esc(k.CASE_TYPE),
          badge(k.STATUS),
          esc(k.MAKER_USER_ID),
          esc(date(k.REVIEW_DUE_AT)),
          button("Open evidence", "case-open", k.CASE_ID),
        ]),
      ),
      can("CIF_UPDATE") ? button("Open new case", "case-new") : "",
    ) +
    panel(
      "Consent evidence",
      table(
        ["Purpose", "Channel / evidence", "Status", ""],
        d.consents.map((c) => [
          esc(c.PURPOSE_CODE),
          `${esc(c.CAPTURE_CHANNEL)}<span class="sub">${esc(c.EVIDENCE_REF)}</span>`,
          badge(c.STATUS),
          c.STATUS === "GRANTED" && can("CIF_UPDATE")
            ? button("Withdraw", "consent-withdraw", c.CONSENT_ID)
            : "",
        ]),
      ),
      can("CIF_UPDATE") ? button("Capture consent", "consent-new") : "",
    ) +
    panel(
      "Party relationships",
      table(
        ["Type", "Target party", "Authority", "Until", ""],
        d.relationships.map((r) => [
          esc(r.RELATIONSHIP_TYPE),
          esc(r.TARGET_PARTY_ID),
          esc(r.OPERATING_AUTHORITY),
          esc(date(r.VALID_TO)),
          !r.VALID_TO && r.SOURCE_PARTY_ID === c.PARTY_ID && can("CIF_UPDATE")
            ? button("End", "relationship-end", r.RELATIONSHIP_ID)
            : "",
        ]),
      ),
      can("CIF_UPDATE") ? button("Add relationship", "relationship-new") : "",
    );
  if (state.case) html += await caseView();
  const events = await api(`/cif/customers/${state.customer}/audit`);
  html += panel(
    "Customer audit",
    table(
      ["Event", "Result", "Actor", "Time"],
      events.map((e) => [
        esc(e.EVENT_TYPE),
        badge(e.RESULT),
        esc(e.ACTOR_USER_ID),
        esc(date(e.OCCURRED_AT)),
      ]),
    ),
  );
  return organizePanels(html, "customer", [
    ["profile", "Profile", ["Customer profile", "Names & history", "Contacts", "Identifiers", "Addresses"]],
    ["kyc", "KYC and evidence", ["KYC cases", "Evidence"]],
    ["relationships", "Relationships and consent", ["Consent evidence", "Party relationships"]],
    ["audit", "Audit", ["Customer audit"]],
  ]);
}

/** Evidence actions reflect case state, while the backend independently checks maker/checker rules. */
async function caseView() {
  const d = await api(`/cif/cases/${state.case}`);
  state.caseDetail = d;
  const k = d.case;
  const maker = k.MAKER_USER_ID === getSession().user.userId;
  const editable = [
    "DRAFT",
    "DOCUMENTS_PENDING",
    "MORE_INFO_REQUIRED",
  ].includes(k.STATUS);
  const reviewable = ["SUBMITTED", "UNDER_REVIEW"].includes(k.STATUS);
  return panel(
    `Evidence · ${k.CASE_TYPE}`,
    `<div class="panel-body">${details(k)}<p class="hint">Identity and address evidence are required for this review. Record the document verification outcome before deciding the case.</p></div>` +
      table(
        ["Document / version", "Size / type", "Scan", "Verification", ""],
        d.documents.map((doc) => [
          `${esc(doc.DOCUMENT_TYPE)} · v${esc(doc.VERSION_NO)}<span class="sub">${esc(doc.DOCUMENT_ID)}</span>`,
          `${esc(doc.BYTE_SIZE)} bytes<span class="sub">${esc(doc.MIME_TYPE)}</span>`,
          badge(doc.SCAN_STATUS),
          badge(doc.VERIFICATION_STATUS),
          `${button("Download", "document-download", doc.DOCUMENT_ID)}${reviewable && !maker && can("KYC_REVIEW") ? button("Review evidence", "document-review", doc.DOCUMENT_ID) : ""}`,
        ]),
      ) +
      table(
        ["Review", "Reason", "Comments", "Reviewer"],
        d.reviews.map((r) => [
          badge(r.OUTCOME),
          esc(r.REASON_CODE),
          esc(r.COMMENTS),
          esc(r.REVIEWER_USER_ID),
        ]),
      ),
    `${editable && maker && can("KYC_UPLOAD") ? button("Upload document", "document-upload") : ""}${editable && maker && can("CIF_UPDATE") ? button("Submit case", "case-submit") : ""}${reviewable && can("KYC_REVIEW") ? button("Assign officer", "case-assign") : ""}${reviewable && !maker && can("KYC_REVIEW") ? button("Decide KYC", "case-review") : ""}`,
  );
}

/** Product directory carries explicit branch context so branch-scoped staff do not get global access. */
export async function productsScreen() {
  syncUser();
  if (getSession().user.userType === "CUSTOMER")
    return (
      heading(
        "Available products",
        "03",
        "Offers use your linked customer profile and current KYC status.",
      ) +
      panel(
        "Discover offers",
        `<div class="panel-body">${button("Browse my offers", "customer-discover")}<div id="customer-offers"></div></div>`,
      )
    );
  if (!state.schema.length) state.schema = await api("/products/rule-schema");
  if (state.version) return versionView();
  if (state.product) return productView();
  const rows = await api(
    `/products?search=${encodeURIComponent(state.search)}&branch=${encodeURIComponent(state.branch)}`,
  );
  return (
    heading(
      "Product master",
      "03",
      "Versioned terms, governed approval and exact adoption references.",
      can("PRODUCT_CREATE") ? button("Create product", "product-new") : "",
    ) +
    panel(
      "Catalogue",
      `<form id="product-search" class="toolbar">${field("Search code or name", "search", "search", { required: false, value: state.search })}${field("Branch context", "branch", "text", { required: false, value: state.branch, placeholder: "Blank for global administrators" })}<button class="btn">Search</button></form>` +
        table(
          ["Code", "Product", "Type", "Currency", "Status", "Sales start", "Action"],
          rows.map((p) => [
            esc(p.PRODUCT_CODE),
            `<strong>${esc(p.PRODUCT_NAME)}</strong>`,
            esc(p.PRODUCT_TYPE),
            esc(p.CURRENCY_CODE),
            badge(p.STATUS),
            esc(date(p.SALES_START_AT)),
            button("View product", "product-open", p.PRODUCT_ID),
          ]),
        ),
    ) +
    panel(
      "Consumer tools",
      '<div class="panel-body"><p>Find currently available products for a branch, customer segment, channel and currency.</p>' +
        button("Discover products", "discover") +
        "</div>",
    )
  );
}

/** Product identity and version history remain distinct in the review UI. */
async function productView() {
  const d = await api(`/products/${state.product}${query()}`);
  state.productDetail = d;
  const p = d.product;
  return organizePanels((
    heading(
      p.PRODUCT_NAME,
      "03",
      `${p.PRODUCT_CODE} · ${p.PRODUCT_TYPE} · ${p.CURRENCY_CODE}`,
      button("Back to catalogue", "product-back"),
    ) +
    panel(
      "Product identity",
      `<div class="panel-body">${details(p)}</div>`,
      can("PRODUCT_CREATE")
        ? `${button("New draft", "version-new")}${button("Request lifecycle change", "product-lifecycle")}${button("Propose treatment", "treatment-new")}`
        : "",
    ) +
    panel(
      "Versions",
      table(
        ["Version", "Status", "Effective from", "Effective to", "Action"],
        d.versions.map((v) => [
          `v${esc(v.VERSION_NO)}`,
          badge(v.VERSION_STATE),
          esc(date(v.EFFECTIVE_FROM_AT)),
          esc(date(v.EFFECTIVE_TO_AT)),
          button("View version", "version-open", v.PRODUCT_VERSION_ID),
        ]),
      ),
      button("Compare versions", "version-compare"),
    ) +
    approvalPanel(d.approvals) +
    panel(
      "Existing-account treatment",
      table(
        ["Source → target", "Policy", "Migration date", "Consent"],
        d.treatments.map((t) => [
          `${esc(t.SOURCE_VERSION_ID)} → ${esc(t.TARGET_VERSION_ID)}`,
          esc(t.TREATMENT_CODE),
          esc(date(t.MIGRATION_FROM_AT)),
          esc(t.CONSENT_REQUIRED),
        ]),
      ),
    ) +
    panel(
      "Eligibility",
      `<div class="panel-body"><p>Check a real customer against the exact active version. This does not open an account.</p>${button("Evaluate customer", "eligibility")}</div>`,
    ) +
    panel(
      "Product audit",
      table(
        ["Event", "Actor", "Result", "Time"],
        (await api(`/products/${state.product}/audit${query()}`)).map((e) => [
          esc(e.EVENT_TYPE),
          esc(e.ACTOR_USER_ID),
          badge(e.RESULT),
          esc(date(e.OCCURRED_AT)),
        ]),
      ),
    )
  ), "product", [
    ["overview", "Product", ["Product identity", "Versions"]],
    ["governance", "Governance", ["Approval history", "Existing-account treatment", "Eligibility"]],
    ["audit", "Audit", ["Product audit"]],
  ]);
}

/** Typed rule families are edited through schema metadata, never unrestricted JSON or executable formulas. */
async function versionView() {
  const d = await api(`/products/versions/${state.version}${query()}`);
  state.versionDetail = d;
  state.product = d.product.PRODUCT_ID;
  const v = d.version;
  const editable =
    v.VERSION_STATE === "DRAFT" &&
    v.CREATED_BY_USER_ID === getSession().user.userId &&
    !d.approvals.some((a) => a.REQUEST_STATUS === "PENDING");
  let html =
    heading(
      `${d.product.PRODUCT_NAME} · v${v.VERSION_NO}`,
      "03",
      `${v.VERSION_STATE} · ${v.CHANGE_REASON}`,
      button("Back to product", "version-back"),
    ) +
    panel(
      "Version governance",
      `<div class="panel-body">${details(v)}<p class="hint">${d.validation.length ? esc(d.validation.join(" · ")) : "Rule completeness checks pass. Approval still checks overlap and current authority."}</p></div>`,
      `${editable && can("PRODUCT_CREATE") ? button("Edit dates / reason", "version-edit") + button("Submit for approval", "version-submit") : ""}${v.VERSION_STATE === "APPROVED" && can("PRODUCT_APPROVE") ? button("Activate", "version-activate") : ""}${!["DRAFT", "RETIRED"].includes(v.VERSION_STATE) && can("PRODUCT_CREATE") ? button("Request lifecycle change", "version-lifecycle") : ""}`,
    ) +
    approvalPanel(d.approvals);
  for (const family of state.schema) {
    const rows = d.rules[family.code] || [];
    if (!rows.length && !editable) continue;
    const columns = family.fields.map((f) => f.name);
    html += panel(
      label(family.code),
      table(
        [...columns.map(label), "Actions"],
        rows.map((row) => [
          ...columns.map((k) => esc(row[k])),
          editable
            ? `${button("Edit", "rule-edit", `${family.code}|${row[family.key]}`)}${button("Delete", "rule-delete", `${family.code}|${row[family.key]}`)}`
            : "Read only",
        ]),
      ),
      editable && can("PRODUCT_CREATE")
        ? button(
            family.key === "PRODUCT_VERSION_ID" ? "Set terms" : "Add rule",
            "rule-new",
            family.code,
          )
        : "",
    );
  }
  return organizePanels(html, "version", [
    ["overview", "Version", ["Version governance", "Approval history"]],
    ["pricing", "Pricing rules", ["Interest", "Tiers", "Fees", "Penalties", "Allocation"]],
    ["controls", "Eligibility and controls", ["Eligibility", "Account", "Loan", "Transactions", "Overrides", "Balance", "Limits", "Availability"]],
  ]);
}

/** Review buttons never imply authority: every decision is rechecked by the backend. */
function approvalPanel(rows) {
  if (!rows.length) return "";
  return panel(
    "Approval history",
    table(
      ["Action / ID", "Status", "Reason / impact", "Maker / checker", ""],
      rows.map((a) => [
        `${esc(a.ACTION_CODE)}<span class="sub">${esc(a.APPROVAL_ID)}</span>`,
        badge(a.REQUEST_STATUS),
        `${esc(a.REASON)}<span class="sub">${esc(a.IMPACT_SUMMARY)}</span>`,
        `${esc(a.MAKER_USER_ID)}<span class="sub">${esc(a.CHECKER_USER_ID)}</span>`,
        a.REQUEST_STATUS === "PENDING"
          ? `${can("PRODUCT_APPROVE") && a.MAKER_USER_ID !== getSession().user.userId ? button("Decide", "product-decision", a.APPROVAL_ID) : ""}${a.MAKER_USER_ID === getSession().user.userId ? button("Withdraw", "product-withdraw", a.APPROVAL_ID) : ""}`
          : "",
      ]),
    ),
  );
}

/** Rule editor uses typed fields and lets the database supply declared defaults for omitted inputs. */
function ruleForm(code, row = null) {
  const family = state.schema.find((f) => f.code === code);
  let body = "";
  for (const f of family.fields) {
    const required = !f.nullable && !f.definition.includes("DEFAULT");
    let type =
      f.javaType === "LocalDate"
        ? "date"
        : f.javaType === "OffsetDateTime"
          ? "datetime-local"
          : "text";
    let value = row?.[f.name] ?? "";
    if (value && type === "datetime-local") value = local(value);
    body += field(label(f.name), f.name, type, {
      required,
      value,
      placeholder: f.definition.match(/DEFAULT ([^ ]+)/)?.[0] || f.javaType,
    });
  }
  dialog(
    `${row ? "Edit" : "Add"} ${label(code)}`,
    grid(body) + `<p class="hint">${esc(ruleHelp(code))}</p>`,
    async (data) => {
      const values = {};
      for (const f of family.fields) {
        let value = data.get(f.name);
        if (value === null || value === "") {
          if (row && f.nullable) values[f.name] = null;
          continue;
        }
        if (f.javaType === "OffsetDateTime") value = iso(value);
        values[f.name] = value;
      }
      const route = `/products/versions/${state.version}/rules/${code}${row ? "/" + row[family.key] : ""}${query()}`;
      await send(
        route,
        { rowVersion: state.versionDetail.version.ROW_VERSION, values },
        row ? "PUT" : "POST",
      );
      await repaint();
    },
  );
}
function ruleHelp(code) {
  return (
    {
      interest:
        "Type FIXED or FLOATING; method SIMPLE, COMPOUND, TIERED, REDUCING_BALANCE or FLAT. Day count ACT_365, ACT_360, ACT_ACT or 30_360. Fixed uses only fixed rate; floating uses index and spread.",
      tiers:
        "Select an interest rule ID from this version. Bands are [from,to), with no overlaps.",
      fees: "Basis FIXED uses only amount; RATE uses only percentage.",
      penalties: "Formula FIXED uses only amount; RATE uses only percentage.",
      eligibility:
        "Attributes AGE, KYC_STATUS, SEGMENT, PARTY_TYPE, COUNTRY, RISK_LEVEL, INCORPORATED_ON. Operators EQ, NE, GT, GE, LT, LE, IN, NOT_IN. Set exactly one typed value.",
      account:
        "Flags Y/N. Minor allowance needs a daily limit. Non-joint products need exactly one holder.",
      loan: "Enter the permitted loan category, repayment frequency, amortization method and disbursement terms.",
      transactions: "Direction DEBIT/CREDIT/BOTH; action ALLOW/BLOCK.",
      overrides:
        "Rule family uses the catalogue family code, such as interest. Reference an existing rule code and numeric field.",
      allocation:
        "Flow DEBIT/CREDIT; payment kind REGULAR/PREPAYMENT/RECOVERY; component PENALTY/FEE/INTEREST/PRINCIPAL/UNAPPLIED; future NEXT_DUE/EARLIEST/LATEST.",
    }[code] ||
    "Dates must increase and all numeric ranges must be coherent. Blank optional fields use schema defaults."
  );
}

/** Binds search forms after a view is replaced. */
export function bindDomainForms() {
  const c = document.querySelector("#customer-search");
  if (c)
    c.onsubmit = async (e) => {
      e.preventDefault();
      state.search = new FormData(c).get("search") || "";
      await repaint();
    };
  const p = document.querySelector("#product-search");
  if (p)
    p.onsubmit = async (e) => {
      e.preventDefault();
      const data = new FormData(p);
      state.search = data.get("search") || "";
      state.branch = data.get("branch") || "";
      await repaint();
    };
}

/** All actions submit explicit DTOs; dialogs preserve validation errors until corrected. */
document.addEventListener("click", async (event) => {
  const tab = event.target.closest("[data-domain-tab]");
  if (tab) {
    const [scope, id] = tab.dataset.domainTab.split(":");
    state[`${scope}Tab`] = id;
    document.querySelectorAll(`[data-domain-tab^="${scope}:"]`).forEach(button => button.setAttribute("aria-selected", String(button === tab)));
    document.querySelectorAll(`[data-domain-panel^="${scope}:"]`).forEach(panel => { panel.hidden = panel.dataset.domainPanel !== tab.dataset.domainTab; });
    return;
  }
  const target = event.target.closest("[data-domain-action]");
  if (!target) return;
  const action = target.dataset.domainAction,
    id = target.dataset.id;
  target.disabled = true;
  const c = state.customerDetail?.customer,
    k = state.caseDetail?.case,
    v = state.versionDetail?.version;
  const customerPath = `/cif/customers/${state.customer}`;
  const save = async (path, body, method = "POST") => {
    await send(path, body, method);
    toast("Saved.");
    await repaint();
  };
  try {
    switch (action) {
      case "customer-open":
        state.customer = id;
        state.case = null;
        state.customerTab = "profile";
        await repaint();
        break;
      case "customer-back":
        state.customer = null;
        state.case = null;
        state.customerTab = "profile";
        await repaint();
        break;
      case "customer-new":
        dialog(
          "Create customer",
          grid(
            select("Party type", "partyType", ["INDIVIDUAL", "ORGANIZATION"]) +
              field("Legal name", "legalName") +
              field("Birth date", "dateOfBirth", "date", { required: false }) +
              field("Incorporation date", "incorporatedOn", "date", {
                required: false,
              }) +
              field("Home branch", "homeBranchRef", "text") +
              field("Segment", "segmentCode", "text", {
                required: false,
                value: "",
              }),
          ),
          async (data) => {
            const result = await send("/cif/customers", object(data));
            state.customer = result.customer.CIF_ID;
            await repaint();
          },
        );
        break;
      case "profile-edit":
        dialog(
          "Propose profile revision",
          grid(
            field("Legal name", "legalName", "text", {
              value: state.customerDetail.names.find((n) => !n.VALID_TO)
                ?.FULL_NAME,
            }) +
              field("Segment", "segmentCode", "text", {
                required: false,
                value: c.SEGMENT_CODE,
              }),
          ) + reason(),
          (data) =>
            save(
              customerPath + "/profile",
              { ...object(data), rowVersion: c.ROW_VERSION },
              "PUT",
            ),
        );
        break;
      case "contact-new":
        dialog(
          "Add encrypted contact",
          grid(
            select("Type", "contactType", ["MOBILE", "EMAIL"]) +
              field("Value", "value"),
          ),
          (data) => save(customerPath + "/contacts", object(data)),
        );
        break;
      case "identifier-new":
        dialog(
          "Add encrypted identifier",
          grid(
            field("Type", "identifierType", "text", { value: "TAX_ID" }) +
              field("Value", "value") +
              field("Issuer", "issuerCode", "text", { required: false }) +
              field("Expires", "expiresOn", "date", { required: false }),
          ),
          (data) => save(customerPath + "/identifiers", object(data)),
        );
        break;
      case "address-new":
        dialog(
          "Propose address",
          grid(
            select("Address type", "addressType", [
              "RESIDENTIAL",
              "MAILING",
              "REGISTERED",
            ]) +
              field("Line 1", "line1") +
              field("Line 2", "line2", "text", { required: false }) +
              field("City", "city") +
              field("Region", "region", "text", { required: false }) +
              field("Postal code", "postalCode", "text", { required: false }) +
              field("Country", "countryCode", "text", { value: "IN" }) +
              field("Evidence document ID", "evidenceDocumentId", "text", {
                required: false,
              }),
          ),
          (data) => save(customerPath + "/addresses", object(data)),
        );
        break;
      case "customer-status":
        dialog(
          "Change customer status",
          select("Status", "status", [
            "ACTIVE",
            "RESTRICTED",
            "DORMANT",
            "CLOSED",
          ]) + reason(),
          (data) =>
            save(
              customerPath + "/status",
              { ...object(data), rowVersion: c.ROW_VERSION },
              "PATCH",
            ),
        );
        break;
      case "case-new":
        dialog(
          "Open KYC case",
          select("Type", "caseType", ["ONBOARDING", "PERIODIC", "REMEDIATION"]),
          async (data) => {
            const result = await send(customerPath + "/cases", object(data));
            state.case = result.CASE_ID;
            state.customerTab = "kyc";
            await repaint();
          },
        );
        break;
      case "case-open":
        state.case = id;
        state.customerTab = "kyc";
        await repaint();
        document
          .querySelector('[data-domain-panel="customer:kyc"]')
          ?.scrollIntoView({ behavior: "smooth" });
        break;
      case "case-submit":
        dialog("Submit KYC case", reason(), (data) =>
          save(`/cif/cases/${state.case}/submit`, {
            rowVersion: k.ROW_VERSION,
            reason: data.get("reason"),
          }),
        );
        break;
      case "case-assign":
        dialog(
          "Assign independent officer",
          field("Officer user ID", "officerUserId"),
          (data) =>
            save(`/cif/cases/${state.case}/assign`, {
              ...object(data),
              rowVersion: k.ROW_VERSION,
            }),
        );
        break;
      case "case-review":
        dialog(
          "Independent KYC decision",
          grid(
            select("Outcome", "outcome", [
              "APPROVE",
              "REQUEST_INFO",
              "REJECT",
            ]) +
              select("Risk", "riskLevel", ["LOW", "MEDIUM", "HIGH"]) +
              field("Periodic review due", "reviewDueAt", "datetime-local", {
                required: false,
              }) +
              field("Reason code", "reasonCode", "text"),
          ) + field("Review comments", "comments"),
          (data) =>
            save(`/cif/cases/${state.case}/review`, {
              ...object(data),
              reviewDueAt: iso(data.get("reviewDueAt")),
              rowVersion: k.ROW_VERSION,
            }),
        );
        break;
      case "document-upload":
        dialog(
          "Upload encrypted evidence",
          select("Document type", "documentType", [
            "IDENTITY",
            "ADDRESS",
            "OTHER",
          ]) + field("PDF, PNG or JPEG · max 5 MiB", "file", "file"),
          (data) => save(`/cif/cases/${state.case}/documents`, data),
        );
        break;
      case "document-download":
        await download(`/cif/documents/${id}/content`);
        break;
      case "document-review":
        dialog(
          "Independent evidence attestation",
          '<p class="hint">Enter the result of the independent document scan and evidence review. Select Clean only after confirming the scan result.</p>' +
            grid(
              select("Scan result", "scanStatus", [
                "CLEAN",
                "QUARANTINED",
                "FAILED",
              ]) +
                select("Verification", "verificationStatus", [
                  "VERIFIED",
                  "REJECTED",
                ]),
            ) +
            reason(),
          (data) => save(`/cif/documents/${id}/review`, object(data)),
        );
        break;
      case "consent-new":
        dialog(
          "Capture consent evidence",
          grid(
            field("Purpose code", "purposeCode") +
              field("Channel", "captureChannel", "text") +
              field("Evidence reference", "evidenceRef"),
          ),
          (data) => save(customerPath + "/consents", object(data)),
        );
        break;
      case "consent-withdraw":
        dialog("Withdraw consent", reason(), () =>
          save(customerPath + `/consents/${id}`, undefined, "DELETE"),
        );
        break;
      case "relationship-new":
        dialog(
          "Add party relationship",
          grid(
            field("Target CIF ID", "targetCifId") +
              field("Relationship type", "relationshipType", "text") +
              select("Operating authority", "operatingAuthority", [
                "NONE",
                "VIEW",
                "TRANSACT",
                "REPRESENT",
              ]) +
              field("Valid until", "validTo", "datetime-local", {
                required: false,
              }),
          ),
          (data) =>
            save(customerPath + "/relationships", {
              ...object(data),
              validTo: iso(data.get("validTo")),
            }),
        );
        break;
      case "relationship-end":
        dialog("End relationship", reason(), () =>
          save(customerPath + `/relationships/${id}`, undefined, "DELETE"),
        );
        break;
      case "expire-reviews":
        dialog(
          "Expire due KYC reviews",
          "<p>Marks due verified KYC as expired and restricts active customer relationships within your review scope.</p>",
          async () => {
            const count = await send("/cif/reviews/expire-due", {});
            toast(`${count} due reviews expired.`);
            await repaint();
          },
        );
        break;
      case "product-open":
        state.product = id;
        state.productTab = "overview";
        await repaint();
        break;
      case "product-back":
        state.product = null;
        state.productTab = "overview";
        state.version = null;
        await repaint();
        break;
      case "product-new":
        dialog(
          "Create product identity",
          grid(
            field("Product code", "productCode") +
              field("Name", "productName") +
              select("Type", "productType", [
                "SAVINGS",
                "CURRENT",
                "DEPOSIT",
                "LOAN",
              ]) +
              field("Currency", "currencyCode", "text") +
              field("Business owner", "businessOwnerRef", "text") +
              field("Manager", "productManagerRef", "text", {
                required: false,
              }) +
              field("Support", "supportRef", "text", { required: false }) +
              field("Description", "description", "text", { required: false }) +
              field("Sales start", "salesStartAt", "datetime-local") +
              field("Sales end", "salesEndAt", "datetime-local", {
                required: false,
              }),
          ),
          async (data) => {
            const result = await send("/products" + query(), {
              ...object(data),
              salesStartAt: iso(data.get("salesStartAt")),
              salesEndAt: iso(data.get("salesEndAt")),
            });
            state.product = result.product.PRODUCT_ID;
            await repaint();
          },
        );
        break;
      case "version-new":
        draftDialog(false);
        break;
      case "version-edit":
        draftDialog(true);
        break;
      case "version-open":
        state.version = id;
        state.versionTab = "overview";
        await repaint();
        break;
      case "version-back":
        state.version = null;
        state.versionTab = "overview";
        await repaint();
        break;
      case "rule-new":
        ruleForm(id);
        break;
      case "rule-edit": {
        const [family, key] = id.split("|");
        const f = state.schema.find((f) => f.code === family);
        ruleForm(
          family,
          state.versionDetail.rules[family].find(
            (r) => String(r[f.key]) === key,
          ),
        );
        break;
      }
      case "rule-delete": {
        const [family, key] = id.split("|");
        dialog("Delete draft rule", reason(), () =>
          save(
            `/products/versions/${state.version}/rules/${family}/${key}?rowVersion=${v.ROW_VERSION}&branch=${encodeURIComponent(state.branch)}`,
            undefined,
            "DELETE",
          ),
        );
        break;
      }
      case "version-submit":
      case "version-activate":
        dialog(
          action === "version-submit"
            ? "Submit immutable revision"
            : "Activate approved revision",
          reason() +
            field("Impact summary", "impactSummary", "text", {
              required: false,
            }),
          (data) =>
            save(
              `/products/versions/${state.version}/${action === "version-submit" ? "submit" : "activate"}${query()}`,
              { ...object(data), rowVersion: v.ROW_VERSION },
            ),
        );
        break;
      case "product-decision": {
        const rows = state.version
          ? state.versionDetail.approvals
          : state.productDetail.approvals;
        const a = rows.find((a) => String(a.APPROVAL_ID) === id);
        dialog(
          "Independent product decision",
          details(a) +
            select("Decision", "decision", ["APPROVED", "REJECTED"]) +
            field("Checker comment", "comment"),
          (data) =>
            save(`/products/approvals/${id}/decision${query()}`, {
              ...object(data),
              rowVersion: a.ROW_VERSION,
            }),
        );
        break;
      }
      case "product-withdraw": {
        const a = (
          state.version
            ? state.versionDetail.approvals
            : state.productDetail.approvals
        ).find((a) => String(a.APPROVAL_ID) === id);
        dialog("Withdraw pending proposal", reason(), () =>
          save(
            `/products/approvals/${id}?rowVersion=${a.ROW_VERSION}&branch=${encodeURIComponent(state.branch)}`,
            undefined,
            "DELETE",
          ),
        );
        break;
      }
      case "product-lifecycle":
      case "version-lifecycle":
        dialog(
          "Request governed lifecycle change",
          select("Action", "action", ["SUSPEND", "RETIRE", "REACTIVATE"]) +
            reason(),
          (data) =>
            save(
              `/products/${state.product}/lifecycle?branch=${encodeURIComponent(state.branch)}${action === "version-lifecycle" ? "&versionId=" + state.version : ""}`,
              {
                ...object(data),
                rowVersion:
                  action === "version-lifecycle"
                    ? v.ROW_VERSION
                    : state.productDetail.product.ROW_VERSION,
              },
            ),
        );
        break;
      case "treatment-new":
        dialog(
          "Propose existing-account treatment",
          grid(
            field("Source version ID", "sourceVersionId") +
              field("Target version ID", "targetVersionId") +
              select("Treatment", "treatmentCode", [
                "GRANDFATHER",
                "SCHEDULED",
                "RENEWAL",
                "MANDATORY",
                "OPT_IN",
              ]) +
              field("Migration from", "migrationFromAt", "datetime-local", {
                required: false,
              }) +
              select("Require consent", "consentRequired", [
                { value: "false", label: "No" },
                { value: "true", label: "Yes" },
              ]),
          ) + reason(),
          (data) =>
            save(`/products/${state.product}/treatments${query()}`, {
              ...object(data),
              migrationFromAt: iso(data.get("migrationFromAt")),
              consentRequired: data.get("consentRequired") === "true",
            }),
        );
        break;
      case "discover":
        dialog(
          "Discover active products",
          grid(
            field("Branch", "branch", "text", { value: state.branch }) +
              field("Segment", "segment", "text", {
                required: false,
              }) +
              field("Channel", "channel", "text", { value: "BRANCH" }) +
              field("Currency", "currency", "text", { value: "INR" }),
          ) + '<div id="offer-result"></div>',
          async (data) => {
            const rows = await api(
              "/products/discover?" + new URLSearchParams(object(data)),
            );
            document.querySelector("#offer-result").innerHTML = table(
              ["Code", "Version", "Type"],
              rows.map((r) => [
                esc(r.product.PRODUCT_CODE),
                esc(r.version.VERSION_NO),
                esc(r.product.PRODUCT_TYPE),
              ]),
            );
            return false;
          },
        );
        break;
      case "customer-discover":
        dialog(
          "Browse my offers",
          grid(
            select("Linked customer", "cifId", getSession().user.cifIds) +
              field("Channel", "channel", "text") +
              field("Currency", "currency", "text"),
          ) + '<div id="offer-result"></div>',
          async (data) => {
            const rows = await api(
              "/products/customer-offers?" + new URLSearchParams(object(data)),
            );
            document.querySelector("#offer-result").innerHTML = table(
              ["Product", "Version", "Type", ""],
              rows.map((r) => [
                esc(r.product.PRODUCT_NAME),
                esc(r.version.VERSION_NO),
                esc(r.product.PRODUCT_TYPE),
                button(
                  "Check eligibility",
                  "customer-eligibility",
                  r.product.PRODUCT_ID,
                ),
              ]),
            );
            return false;
          },
        );
        break;
      case "customer-eligibility":
        state.product = id;
      case "eligibility":
        dialog(
          "Evaluate authoritative customer facts",
          grid(
            field("CIF ID", "cifId") +
              field("Channel", "channel", "text") +
              field("Opening amount", "openingAmount", "text", {
                required: false,
              }) +
              field("Loan amount", "loanAmount", "text", { required: false }) +
              field("Tenure months", "tenureMonths", "text", {
                required: false,
              }),
          ),
          async (data) => {
            const result = await send(
              `/products/${state.product}/eligibility`,
              object(data),
            );
            toast(
              result.eligible
                ? "Eligible. Recheck at account opening."
                : `Not eligible: ${result.reasonCodes.join(", ")}`,
            );
          },
        );
        break;
      case "version-compare":
        dialog(
          "Compare product versions",
          grid(
            field("Left version ID", "left") +
              field("Right version ID", "right"),
          ) + '<div id="comparison-result"></div>',
          async (data) => {
            const result = await api(
              `/products/compare?left=${encodeURIComponent(data.get("left"))}&right=${encodeURIComponent(data.get("right"))}&branch=${encodeURIComponent(state.branch)}`,
            );
            const { mountComparison } = await import("./comparisons.js");
            const dispose = mountComparison(
              document.querySelector("#comparison-result"),
              result,
            );
            document
              .querySelector("#dialog")
              .addEventListener("close", dispose, { once: true });
            return false;
          },
        );
        break;
    }
  } catch (error) {
    toast(error.message, true);
  } finally {
    target.disabled = false;
  }
});

/** Common draft editor is shared by initial creation, clone and pre-submission revision. */
function draftDialog(edit) {
  const v = state.versionDetail?.version;
  dialog(
    edit ? "Edit draft revision" : "Create / clone draft",
    grid(
      (edit
        ? ""
        : field("Clone source version ID", "sourceVersionId", "text", {
            required: false,
          })) +
        field("Effective from", "effectiveFromAt", "datetime-local", {
          value: local(edit ? v.EFFECTIVE_FROM_AT : null),
        }) +
        field("Effective until", "effectiveToAt", "datetime-local", {
          required: false,
          value: edit && v.EFFECTIVE_TO_AT ? local(v.EFFECTIVE_TO_AT) : "",
        }) +
        select(
          "Default transaction action",
          "defaultTxnAction",
          ["BLOCK", "ALLOW"],
          edit ? v.DEFAULT_TXN_ACTION : "BLOCK",
        ) +
        field("Change reason", "changeReason", "text", {
          value: edit ? v.CHANGE_REASON : "",
        }),
    ),
    async (data) => {
      const body = {
        ...object(data),
        effectiveFromAt: iso(data.get("effectiveFromAt")),
        effectiveToAt: iso(data.get("effectiveToAt")),
      };
      if (edit) body.rowVersion = v.ROW_VERSION;
      const result = await send(
        edit
          ? `/products/versions/${state.version}${query()}`
          : `/products/${state.product}/versions${query()}`,
        body,
        edit ? "PUT" : "POST",
      );
      state.version = result.version.PRODUCT_VERSION_ID;
      await repaint();
    },
  );
}

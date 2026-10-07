# English, Hindi and Kannada UI

The language menu is available on the sign-in page and in the workspace header. It supports `en-IN`, `hi-IN` and `kn-IN`. The choice is stored under `moneybags.locale` in the browser and survives sign-out. Switching language reloads the page so Oracle JET loads the corresponding locale data; any unsent form or in-memory chat draft is discarded.

`frontend/i18n.js` holds the UI translations. Add each new user-facing phrase to its `entries` array as `[English, Hindi, Kannada]`. English is the fallback for untranslated copy. OpenAPI-generated field names use `tField()` and a small financial vocabulary when no complete phrase exists. The UI translates labels and displayed statuses; it does not alter API property names, IDs, status codes, account numbers or submitted decimal strings.

Oracle JET reads the chosen locale in `frontend/jet.js`; dates use that locale in `frontend/presentation.js`. `frontend/server.mjs` and `frontend/tools/build.mjs` include the translation module in development and standalone builds.

The assistant UI uses the same language. Chat requests include the selected locale, and the backend accepts only the three supported locale tags before instructing the model which language to answer in. Tool calls, access controls and transaction confirmation remain unchanged. The model provider must still be configured for chat to answer. Existing API errors and any untranslated workflow descriptions fall back to English; extend the catalog when adding or revising screens. Review translated financial and legal wording with a fluent reviewer before production use.

To verify the frontend from `integrated/frontend`, run `npm run check`, `npm test`, and `npm run build`. Start the local app with the repository's existing launcher and switch languages on the sign-in page, then inspect Accounts and Ask Moneybags. The local backend uses the same `POST /api/v1/assistant/chat` endpoint; older clients may omit `locale` and receive English.

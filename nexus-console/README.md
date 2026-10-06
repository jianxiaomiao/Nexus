# nexus-console

This template should help get you started developing with Vue 3 in Vite.

## Recommended IDE Setup

[VS Code](https://code.visualstudio.com/) + [Vue (Official)](https://marketplace.visualstudio.com/items?itemName=Vue.volar) (and disable Vetur).

## Recommended Browser Setup

- Chromium-based browsers (Chrome, Edge, Brave, etc.):
  - [Vue.js devtools](https://chromewebstore.google.com/detail/vuejs-devtools/nhdogjmejiglipccpnnnanhbledajbpd)
  - [Turn on Custom Object Formatter in Chrome DevTools](http://bit.ly/object-formatters)
- Firefox:
  - [Vue.js devtools](https://addons.mozilla.org/en-US/firefox/addon/vue-js-devtools/)
  - [Turn on Custom Object Formatter in Firefox DevTools](https://fxdx.dev/firefox-devtools-custom-object-formatters/)

## Type Support for `.vue` Imports in TS

TypeScript cannot handle type information for `.vue` imports by default, so we replace the `tsc` CLI with `vue-tsc` for type checking. In editors, we need [Volar](https://marketplace.visualstudio.com/items?itemName=Vue.volar) to make the TypeScript language service aware of `.vue` types.

## Customize configuration

See [Vite Configuration Reference](https://vite.dev/config/).

## Project Setup

```sh
npm install
```

### Compile and Hot-Reload for Development

```sh
npm run dev
```

### Type-Check, Compile and Minify for Production

```sh
npm run build
```

### Lint with [ESLint](https://eslint.org/)

```sh
npm run lint
```

## Browser tests (Playwright)

From `nexus-console`, install dependencies and the Chromium browser once:

```sh
npm install
npx playwright install chromium
```

Run the browser test with `npm run test:e2e`. For an interactive, step-by-step view, run `npm run test:e2e:ui`; use `npm run test:e2e -- --headed` to watch the browser. The test runner starts its own Vite server on `127.0.0.1:4173` and closes it afterward. If that port is occupied, stop the other server first.

Run `npm run type-check:e2e` to type-check the Playwright test code separately from the Vue application build.

The tests in `e2e/` intercept backend `/api/` and selected `/v1/` requests and supply fake responses. They cover login/registration, Application and API Key management, short-link creation and management, developer docs, the API playground, and mobile navigation without starting Spring Boot or writing to MySQL. A passing mocked-browser test does **not** prove backend integration. Keep real frontend-to-backend acceptance separate, with a dedicated test database/account and explicit cleanup.

## Short-link public address

The console builds a shareable short URL from the returned `shortCode`. By default it uses the current browser origin plus `/s/{shortCode}`. The Vite development server proxies only `/s/` paths to the backend; the production web entry must route the same path to Nexus instead of the Vue fallback. If short links use a separate public origin, set `VITE_SHORT_LINK_PUBLIC_BASE_URL` to that origin when building the console. This variable is a public address, never a credential.

If a test fails, Playwright saves a screenshot and trace under `test-results/`. Open a trace with `npx playwright show-trace <path-to-trace.zip>`; test artifacts are ignored by Git.

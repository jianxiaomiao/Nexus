// Run with Node, or HBuilderX's plugins/node/node. No dependency installation needed.
const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');

async function build() {
  const root = path.resolve(__dirname, '..');
  const candidates = [
    path.resolve(root, '../nexus-console'),
    process.env.HBUILDERX_PLUGINS && path.join(process.env.HBUILDERX_PLUGINS, 'uniapp-cli-vite'),
    '/Applications/HBuilderX.app/Contents/HBuilderX/plugins/uniapp-cli-vite',
  ].filter(Boolean);
  let esbuild;
  for (const candidate of candidates) {
    try { esbuild = createRequire(path.join(candidate, 'package.json'))('esbuild'); break; } catch {}
  }
  if (!esbuild) throw new Error('Cannot find esbuild. Set HBUILDERX_PLUGINS to the HBuilderX plugins directory.');
  const result = await esbuild.build({
    entryPoints: [path.resolve(root, '../nexus-console/src/components/login-planet/scene.js')],
    bundle: true,
    format: 'iife',
    globalName: 'NexusLoginPlanet',
    platform: 'browser',
    target: 'es2020',
    minify: true,
    legalComments: 'inline',
    write: false,
    banner: { js: '// Generated from nexus-console login-planet sources; run scripts/build-login-planet.cjs to update.' },
  });
  const bundle = result.outputFiles[0].contents;
  const license = fs.readFileSync(path.resolve(root, '../nexus-console/src/components/login-planet/vendor/THREE-LICENSE.txt'));
  // Platform-specific directories keep the Three.js/model bundle out of mini-programs.
  for (const platform of ['app', 'web']) {
    const dir = path.join(root, 'static', platform, 'login-planet');
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(path.join(dir, 'scene.js'), bundle);
    fs.writeFileSync(path.join(dir, 'THREE-LICENSE.txt'), license);
  }
  console.log(`Built local App/Web scene bundles (${bundle.byteLength} bytes each); mini-programs use the poster.`);
}

build().catch(error => { console.error(error.message); process.exitCode = 1; });

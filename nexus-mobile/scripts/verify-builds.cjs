// Reuse HBuilderX's installed compiler; do not create a CLI project or install dependencies.
const fs = require('node:fs')
const path = require('node:path')
const { spawnSync } = require('node:child_process')
const root = path.resolve(__dirname, '..')
const plugins = process.env.NEXUS_HBUILDERX_PLUGINS || '/Applications/HBuilderX.app/Contents/HBuilderX/plugins'
const compiler = path.join(plugins, 'uniapp-cli-vite/node_modules/@dcloudio/vite-plugin-uni/bin/uni.js')
const output = path.join(root, '.runtime/robustness-builds')
fs.mkdirSync(output, { recursive: true })
for (const platform of ['h5', 'app']) {
  const log = fs.openSync(path.join(output, platform + '.log'), 'w', 0o600)
  try {
    const result = spawnSync(process.execPath, [compiler, 'build', '--platform', platform], {
      cwd: root, stdio: ['ignore', log, log],
      env: { ...process.env, UNI_PROJECT_TYPE: 'hbuilder', RUN_BY_HBUILDERX: '1', UNI_HBUILDERX_PLUGINS: plugins,
        UNI_INPUT_DIR: root, UNI_OUTPUT_DIR: path.join(root, 'unpackage/dist/build', platform) },
    })
    if (result.error) throw result.error
    if (result.status !== 0) throw Error(`${platform} compilation failed; inspect .runtime/robustness-builds/${platform}.log`)
    console.log(`${platform} source compilation passed`)
  } finally { fs.closeSync(log) }
}

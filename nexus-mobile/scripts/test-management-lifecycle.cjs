const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

// Exercise the actual page methods; network and native toast are replaced, no Vue runtime or backend.
for (const [page, record] of [['application-detail', 'app'], ['key-detail', 'record']]) {
  test(`${page}: confirmation retains the version read when opened after metadata refresh`, async t => {
    const previous = globalThis.__managementSnapshotApi
    const previousUni = globalThis.uni
    let calls = 0, submitted
    const update = async (...args) => {
      calls++; submitted = args.at(-1)
      throw { code: 'RESOURCE_CHANGED' }
    }
    globalThis.__managementSnapshotApi = {
      updateApplication: update, updateKey: update,
      statusLabel: () => '', statusTone: () => '', nameError: () => '', showError: async () => '资料已修改，请刷新核对。',
    }
    globalThis.uni = { showToast() {} }
    t.after(() => { globalThis.__managementSnapshotApi = previous; globalThis.uni = previousUni })
    let source = fs.readFileSync(path.join(__dirname, `../pages/${page}/${page}.vue`), 'utf8').match(/<script>([\s\S]*?)<\/script>/)[1]
    source = source.replace(/import \* as api from [^\n]+/, 'const api = globalThis.__managementSnapshotApi')
      .replace(/import \{ beijingTime \} from [^\n]+/, 'const beijingTime = () => ""')
    const component = (await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'))).default
    const vm = component.data()
    for (const [name, work] of Object.entries(component.methods)) vm[name] = work.bind(vm)
    const old = '2026-11-19T00:00:00.001Z'
    vm[record] = { id: 1, name: '原名称', status: 0, updatedAt: old }
    vm.visible = true; vm.id = 1; vm.applicationId = 1
    vm.openAction('rename'); vm.formName = '我的修改'
    vm[record] = { ...vm[record], name: '别处已修改', updatedAt: '2026-11-19T00:00:01.001Z' }
    await vm.submitAction()
    assert.equal(submitted.expectedUpdatedAt, old)
    assert.equal(vm.uncertain, true)
    assert.match(vm.notice, /刷新/)
    assert.equal(vm.busy, false)
    await vm.submitAction()
    assert.equal(calls, 1, 'conflict blocks a blind second write')
  })
}

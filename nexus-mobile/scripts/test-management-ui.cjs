// Browser fixtures replace only the platform adapter in this test. Shipping H5 stays preview-only.
const fs = require('node:fs'),
	path = require('node:path'),
	assert = require('node:assert/strict')
const { chromium } = require(
	path.resolve(__dirname, '../../nexus-console/node_modules/playwright'),
)
const { expect } = require(
	path.resolve(
		__dirname,
		'../../nexus-console/node_modules/@playwright/test',
	),
)
const base = process.env.NEXUS_MOBILE_TEST_BASE_URL || 'http://127.0.0.1:5186/'
const output = path.resolve(__dirname, '../.runtime/management-ui')
fs.mkdirSync(output, { recursive: true })
const adapter = `import {createSession} from '/auth/session.js';import {createTransport} from '/api/transport.js';let saved=null;
const request=createTransport({origin:'http://127.0.0.1:18086',send:o=>uni.request(o)});
const auth=createSession({request,vault:{read:()=>saved,write:v=>{saved=v}}});
export const getAuth=async()=>auth;export const register=body=>request('/api/auth/register',{method:'POST',body});
export const authMessage=e=>({'AUTH_INVALID_CREDENTIALS':'邮箱或密码错误','NETWORK_UNAVAILABLE':'连接暂时不可用，请重试','UNSUPPORTED_PLATFORM':'当前平台预览'}[e?.code]||'服务暂不可用');
export const goToLogin=()=>uni.reLaunch({url:'/pages/login/login'});export const goToApplications=()=>uni.reLaunch({url:'/pages/applications/applications'});`
const stamp = '2026-11-18T00:00:00Z'
const initialQuota = {
	periodStart: stamp,
	periodEnd: '2026-12-18T00:00:00Z',
	quotaLimit: 1000,
	usedCount: 7,
	restCount: 993,
}
async function main() {
	const browser = await chromium.launch({
		channel: 'chrome',
		headless: true,
		chromiumSandbox: true,
	})
	try {
		const context = await browser.newContext({
			viewport: { width: 390, height: 844 },
			isMobile: true,
			hasTouch: true,
			timezoneId: 'America/New_York',
		})
		const p = await context.newPage()
		const pageErrors = [],
			calls = [],
			checks = []
		p.on('pageerror', (e) => pageErrors.push(e.message))
		let apps = [],
			keys = [],
			nextApp = 11,
			nextKey = 21,
			rotation = 0,
			logins = 0
		let lostCreate = false,
			lostRotate = false,
			quotaFailed = false,
			slowSummary = false
		let summaryBarrier = null
		const mark = (x) => {
			checks.push(x)
			console.log(x)
		}
		await context.route('**/*', async (route) => {
			const request = route.request(),
				url = new URL(request.url()),
				method = request.method()
			if (url.pathname === '/auth/runtime.js')
				return route.fulfill({
					status: 200,
					contentType: 'application/javascript',
					body: adapter,
				})
			if (url.pathname.endsWith('/login-planet/scene.js'))
				return route.abort()
			if (
				url.origin === new URL(base).origin &&
				url.pathname.endsWith('.js')
			)
				return route.continue()
			if (!/^\/(api|v1)\//.test(url.pathname)) return route.continue()
			const headers = {
				'Access-Control-Allow-Origin': '*',
				'Access-Control-Allow-Headers': 'Content-Type,Authorization',
				'Access-Control-Allow-Methods': 'GET,POST,PUT,DELETE,OPTIONS',
			}
			if (method === 'OPTIONS')
				return route.fulfill({ status: 204, headers })
			calls.push({ path: url.pathname, method, query: url.search })
			assert.equal(
				url.origin,
				'http://127.0.0.1:18086',
				'No unexpected business origin',
			)
			const body = request.postDataJSON() || {}
			const ok = (data, status = 200) =>
				route.fulfill({
					status,
					headers,
					contentType: 'application/json',
					body: JSON.stringify({ code: 'SUCCESS', data }),
				})
			const fail = (code, status) =>
				route.fulfill({
					status,
					headers,
					contentType: 'application/json',
					body: JSON.stringify({
						code,
						message: 'Server private diagnostics must not be shown',
						data: null,
					}),
				})
			const tokens = () => ({
				accessToken: 'fixture-access-' + ++logins,
				refreshToken: String.fromCharCode(65 + logins).repeat(43),
				sessionId: '1',
				tokenType: 'Bearer',
				expiresInSeconds: 1800,
				sessionExpiresAt: '2099-01-01T00:00:00Z',
			})
			if (url.pathname === '/api/auth/register') return ok({ id: 1 }, 201)
			if (
				url.pathname === '/api/auth/login' ||
				url.pathname === '/api/auth/refresh'
			)
				return ok(tokens())
			if (url.pathname === '/api/auth/logout') return ok(null)
			assert.match(
				request.headers().authorization || '',
				/^Bearer fixture-access-/,
			)
			if (url.pathname === '/api/application/create') {
				if (apps.some((a) => a.name === body.name))
					return fail('APPLICATION_NAME_ALREADY_EXISTS', 409)
				const app = {
					id: nextApp++,
					name: body.name,
					status: 0,
					createdAt: stamp,
					updatedAt: stamp,
					quota: { ...initialQuota },
				}
				apps.push(app)
				return ok({ id: app.id, name: app.name })
			}
			if (url.pathname === '/api/application' && method === 'GET') {
				const rows = apps.filter(
					(a) =>
						!url.searchParams.has('applicationId') ||
						a.id === Number(url.searchParams.get('applicationId')),
				)
				return ok({
					records: rows,
					total: rows.length,
					current: Number(url.searchParams.get('current')),
					size: 10,
				})
			}
			if (url.pathname === '/api/application' && method === 'PUT') {
				const app = apps.find((a) => a.id === body.id)
				Object.assign(app, body)
				const { quota, ...result } = app
				return ok(result)
			}
			if (
				/^\/api\/application\/\d+$/.test(url.pathname) &&
				method === 'DELETE'
			) {
				apps = apps.filter(
					(a) => a.id !== Number(url.pathname.split('/').pop()),
				)
				return ok(null)
			}
			if (url.pathname.endsWith('/quota')) {
				if (quotaFailed) return fail('UNAVAILABLE', 503)
				return ok(apps[0].quota)
			}
			if (url.pathname === '/api/apiKey/create') {
				const item = {
					id: nextKey++,
					applicationId: body.applicationId,
					name: body.name,
					publicId: 'public-' + nextKey,
					keyPreview: 'nxk_example_****',
					status: 0,
					createdAt: stamp,
					updatedAt: stamp,
				}
				keys.push(item)
				if (lostCreate) {
					lostCreate = false
					return route.abort()
				}
				return ok({
					...item,
					apiKey: 'FIXTURE_ONLY_CREATION_CREDENTIAL',
				})
			}
			if (/^\/api\/apiKey\/\d+$/.test(url.pathname) && method === 'GET') {
				const rows = keys.filter(
					(k) =>
						k.applicationId ===
							Number(url.pathname.split('/').pop()) &&
						(!url.searchParams.has('apiKeyId') ||
							k.id === Number(url.searchParams.get('apiKeyId'))),
				)
				return ok({
					records: rows,
					total: rows.length,
					current: 1,
					size: 10,
				})
			}
			if (url.pathname === '/api/apiKey' && method === 'PUT') {
				const k = keys.find((k) => k.id === body.apiKeyId)
				Object.assign(k, { ...body, id: k.id })
				return ok(k)
			}
			if (url.pathname === '/api/apiKey' && method === 'DELETE') {
				keys = keys.filter((k) => k.id !== body.apiKeyId)
				return ok(null)
			}
			if (url.pathname.endsWith('/rotate')) {
				const k = keys.find(
					(k) => k.id === Number(url.pathname.split('/')[3]),
				)
				assert.equal(body.expectedPublicId, k.publicId)
				k.publicId = 'rotated-' + ++rotation
				k.keyPreview = 'nxk_rotated_****'
				if (lostRotate) {
					lostRotate = false
					return route.abort()
				}
				return ok({ ...k, apiKey: 'FIXTURE_ONLY_ROTATED_CREDENTIAL' })
			}
			if (url.pathname === '/api/usage/events')
				return ok({
					records: [
						{
							apiCode: 'UUID',
							httpStatusCode: 200,
							durationMs: 9,
							occurredAt: stamp,
						},
					],
					total: 1,
					current: 1,
					size: 10,
				})
			if (url.pathname === '/api/usage') {
				const today = url.searchParams.get('timeRange') === 'TODAY'
				if (summaryBarrier && url.searchParams.get('timeRange') === 'LAST_30_DAYS') {
					const barrier = summaryBarrier; summaryBarrier = null; barrier.enter(); await barrier.promise
				}
				if (slowSummary && !today) {
					slowSummary = false
					await new Promise((r) => setTimeout(r, 700))
				}
				return ok({
					allTimeCount: 7,
					periodCount: today ? 0 : 7,
					timeZone: 'Asia/Shanghai',
					periodStart: stamp,
					periodEndExclusive: '2026-11-19T00:00:00+08:00',
					dailyCounts: [{ date: '2026-11-18', count: today ? 0 : 7 }],
					apiCounts: today
						? []
						: [{ apiCode: 'UUID', apiName: 'UUID生成', count: 7 }],
				})
			}
			throw new Error(
				'Unexpected fixture route ' + method + ' ' + url.pathname,
			)
		})
		const button = (name, scope = p) =>
			scope
				.locator('.nexus-button')
				.filter({ hasText: new RegExp('^' + name + '$') })
		const dialog = () =>
			p
				.locator('.dialog-mask')
				.filter({ has: p.locator('.dialog-title') })
				.last()
		const fill = async (id, value) =>
			p.locator('#' + id + ' input').fill(value)
		await p.goto(base + '#/pages/login/login')
		await button('创建账号').click()
		await expect(p.locator('.register-page')).toBeVisible()
		await button('注册账号').click()
		await expect(p.locator('.nexus-field__message--error')).toHaveCount(4)
		for (const [id, value] of [
			['register-name', '测试开发者'],
			['register-email', 'fixture@example.test'],
			['register-password', 'fixture-password'],
			['register-confirmation', 'fixture-password'],
		])
			await fill(id, value)
		await button('注册账号').click()
		await expect(p.locator('.login-page')).toBeVisible()
		await fill('login-email', 'fixture@example.test')
		await fill('login-password', 'fixture-password')
		await button('登录').click()
		await expect(p.locator('.applications-page')).toBeVisible()
		await expect(
			p.getByText('从第一个 Application 开始', { exact: true }),
		).toBeVisible()
		mark('REGISTER_LOGIN_AND_EMPTY_APPLICATIONS_PASSED')
		await button('创建 Application').click()
		await fill('create-application-name', '移动实验室')
		await button('创建', dialog()).click()
		await expect(p.locator('.application-name')).toHaveText('移动实验室')
		await button('创建 Application').click()
		await fill('create-application-name', '移动实验室')
		await button('创建', dialog()).click()
		await expect(dialog()).toContainText('名称已被使用')
		await button('取消', dialog()).click()
		await button('查看详情').click()
		await expect(p.locator('.application-detail-page')).toBeVisible()
		await expect(p.locator('.quota-amount')).toHaveText('7 / 1000 次')
		await expect(p.locator('.application-detail-page')).toContainText(
			'2026-11-18 08:00',
		)
		await button('编辑名称').click()
		await fill('application-action-name', '移动实验室改名')
		await button('确认保存', dialog()).click()
		await expect(p.locator('.management-title')).toHaveText(
			'移动实验室改名',
		)
		await expect(p.locator('.quota-amount')).toHaveText('7 / 1000 次')
		await button('禁用应用').click()
		await expect(dialog()).toContainText('所有 API Key')
		await button('确认保存', dialog()).click()
		await expect(
			p.locator('.application-detail-page .nexus-badge').first(),
		).toHaveText('已禁用')
		await button('API Keys').click()
		assert.notEqual(
			await button('创建 API Key').getAttribute('disabled'),
			null,
		)
		await button('启用应用').click()
		await button('确认保存', dialog()).click()
		await button('概览').click()
		quotaFailed = true
		await button('刷新配额').click()
		await expect(p.locator('.nexus-state--error')).toContainText(
			'配额暂不可用',
		)
		quotaFailed = false
		await button('重试配额').click()
		await expect(p.locator('.quota-amount')).toHaveText('7 / 1000 次')
		apps[0].quota = {
			...initialQuota,
			quotaLimit: 0,
			usedCount: 0,
			restCount: 0,
		}
		await button('刷新配额').click()
		await expect(p.getByText('无可用额度', { exact: true })).toBeVisible()
		await expect(p.locator('[role=progressbar]')).toHaveAttribute(
			'aria-valuenow',
			'0',
		)
		apps[0].quota = { ...initialQuota }
		await button('刷新配额').click()
		await expect(p.locator('.quota-amount')).toHaveText('7 / 1000 次')
		mark(
			'APPLICATION_CREATE_DUPLICATE_RENAME_STATUS_QUOTA_AND_TIMEZONE_PASSED',
		)
		await button('API Keys').click()
		await button('创建 API Key').click()
		await fill('application-action-name', '移动端 Key')
		await button('创建 Key', dialog()).click()
		await expect(p.locator('.full-key')).toHaveText(
			'FIXTURE_ONLY_CREATION_CREDENTIAL',
		)
		assert.notEqual(await button('完成').getAttribute('disabled'), null)
		await p.evaluate(() => {
			uni.setClipboardData = (options) => {
				window.__copiedFixture = options.data
				options.success()
			}
		})
		await button('复制凭据').click()
		await expect(button('已复制')).toBeVisible()
		assert.equal(
			await p.evaluate(() => window.__copiedFixture),
			'FIXTURE_ONLY_CREATION_CREDENTIAL',
		)
		await p.locator('.save-check').click()
		await button('完成').click()
		await expect(p.locator('.full-key')).toHaveCount(0)
		await button('查看 Key').click()
		await expect(p.locator('.key-detail-page')).toBeVisible()
		await expect(p.locator('.key-preview')).toHaveText('nxk_example_****')
		assert(
			!(await p.locator('body').innerText()).includes(
				'FIXTURE_ONLY_CREATION_CREDENTIAL',
			),
		)
		await button('编辑名称').click()
		await fill('key-action-name', '移动端主 Key')
		await button('确认保存', dialog()).click()
		await expect(p.locator('.management-title')).toHaveText('移动端主 Key')
		await button('禁用 Key').click()
		await button('确认保存', dialog()).click()
		await expect(button('启用 Key')).toBeVisible()
		await button('调用统计').click()
		await expect(p.locator('.all-time-count')).toHaveText('7')
		await expect(p.locator('.period-count')).toHaveText('7')
		await expect(p.locator('.event-row')).toContainText('HTTP 200')
		await button('今天').click()
		await expect(p.locator('.period-count')).toHaveText('0')
		await expect(p.locator('.all-time-count')).toHaveText('7')
		await expect(
			p.getByText('该时段暂无调用记录', { exact: true }),
		).toBeVisible()
		slowSummary = true
		await button('近30天').click()
		await button('今天').click()
		await p.waitForTimeout(850)
		await expect(p.locator('.period-count')).toHaveText('0')
		mark(
			'KEY_ONE_TIME_REVEAL_RENAME_DISABLED_HISTORY_USAGE_ZERO_AND_STALE_RESPONSE_PASSED',
		)
        let releaseSummary, enteredSummary
        const entered = new Promise(resolve => { enteredSummary = resolve })
        summaryBarrier = { enter: enteredSummary, promise: new Promise(resolve => { releaseSummary = resolve }) }
        await button('近30天').click()
        await entered
        await button('自定义').click()
        await expect.poll(() => button('查询时间范围').getAttribute('disabled')).toBeNull()
        releaseSummary()
        await button('查询时间范围').click()
        mark('CUSTOM_SWITCH_DURING_PENDING_QUERY_REMAINS_QUERYABLE_PASSED')
		await expect(p.locator('.period-count')).toHaveText('7')
		const customQuery = new URLSearchParams(
			calls.filter((call) => call.path === '/api/usage').at(-1).query,
		)
		assert.equal(customQuery.get('timeRange'), 'CUSTOM')
		assert.match(
			customQuery.get('customStartTime'),
			/^\d{4}-\d{2}-\d{2}T00:00:00$/,
		)
		assert.match(
			customQuery.get('customEndTime'),
			/^\d{4}-\d{2}-\d{2}T00:00:00$/,
		)
		await button('密钥信息').click()
		await button('启用 Key').click()
		await button('确认保存', dialog()).click()
		await button('轮换 API Key').click()
		await button('确认轮换', dialog()).click()
		await expect(p.locator('.full-key')).toHaveText(
			'FIXTURE_ONLY_ROTATED_CREDENTIAL',
		)
		await p.evaluate(
			() =>
				new Promise((resolve, reject) =>
					uni.navigateTo({
						url: '/pages/component-preview/component-preview',
						success: resolve,
						fail: reject,
					}),
				),
		)
		await expect(p.locator('.preview-page')).toBeVisible()
		await p.evaluate(
			() =>
				new Promise((resolve, reject) =>
					uni.navigateBack({ success: resolve, fail: reject }),
				),
		)
		await expect(p.locator('.key-detail-page')).toBeVisible()
		await expect(p.locator('.full-key')).toHaveCount(0)
		await expect(p.locator('.key-detail-page')).toContainText(
			'完整 Key 已在切换页面时隐藏',
		)
		await expect(p.locator('.public-id')).toHaveText('rotated-1')
		lostRotate = true
		await button('轮换 API Key').click()
		await button('确认轮换', dialog()).click()
		await expect(dialog()).toContainText('结果尚未确认')
		assert.notEqual(
			await button('确认轮换', dialog()).getAttribute('disabled'),
			null,
		)
		assert.equal(rotation, 2)
		await button('取消', dialog()).click()
		await button('刷新资料').click()
		await expect(p.locator('.public-id')).toHaveText('rotated-2')
		await expect(p.locator('.full-key')).toHaveCount(0)
		await p.screenshot({
			path: output + '/key-detail-390.png',
			fullPage: true,
		})
		await button('删除 Key').click()
		assert.notEqual(
			await button('确认删除', dialog()).getAttribute('disabled'),
			null,
		)
		await fill('delete-key-name', '移动端主 Key')
		await button('确认删除', dialog()).click()
		await expect(p.locator('.application-detail-page')).toBeVisible()
		await expect(
			p.getByText('还没有 API Key', { exact: true }),
		).toBeVisible()
		lostCreate = true
		await button('创建 API Key').click()
		await fill('application-action-name', '响应丢失的 Key')
		await button('创建 Key', dialog()).click()
		await expect(dialog()).toContainText('结果尚未确认')
		assert.equal(keys.length, 1)
		assert.notEqual(
			await button('创建 Key', dialog()).getAttribute('disabled'),
			null,
		)
		await button('取消', dialog()).click()
		await button('刷新 Key 列表').click()
		await expect(
			p.getByText('响应丢失的 Key', { exact: true }),
		).toBeVisible()
		mark(
			'KEY_ROTATION_CONFLICT_PROTECTION_LOST_RESPONSES_AND_STRONG_DELETE_PASSED',
		)
		await button('概览').click()
		await p.setViewportSize({ width: 320, height: 740 })
		assert(
			await p.evaluate(
				() => document.documentElement.scrollWidth <= innerWidth,
			),
		)
		await p.screenshot({
			path: output + '/application-detail-320.png',
			fullPage: true,
		})
		await button('删除应用').click()
		assert.notEqual(
			await button('确认删除', dialog()).getAttribute('disabled'),
			null,
		)
		await fill('delete-application-name', '移动实验室改名')
		await button('确认删除', dialog()).click()
		await expect(p.locator('.applications-page')).toBeVisible()
		await expect(
			p.getByText('从第一个 Application 开始', { exact: true }),
		).toBeVisible()
		await button('退出登录').click()
		await button('确认退出', dialog()).click()
		await expect(p.locator('.login-page')).toBeVisible()
		const storage = await p.evaluate(() =>
			JSON.stringify({ ...localStorage, ...sessionStorage }),
		)
		assert(!storage.includes('FIXTURE_ONLY_'))
		assert(!storage.includes('fixture-password'))
		assert.deepEqual(pageErrors, [])
		fs.writeFileSync(
			output + '/result.json',
			JSON.stringify(
				{
					passed: true,
					checks,
					requestCount: calls.length,
					realBackend: false,
					at: new Date().toISOString(),
				},
				null,
				2,
			),
		)
		console.log(
			'ALL_MANAGEMENT_BROWSER_FIXTURES_PASSED; no real backend calls',
		)
	} finally {
		await browser.close()
	}
}
main().catch((e) => {
	console.error(e.stack)
	process.exitCode = 1
})

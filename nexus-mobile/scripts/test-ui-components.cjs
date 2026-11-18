// Start the HBuilderX Web preview first. This check makes no business API calls.
const path = require('node:path')
const fs = require('node:fs')
const assert = require('node:assert/strict')
const { chromium } = require(
	path.resolve(__dirname, '../../nexus-console/node_modules/playwright'),
)
const { expect } = require(
	path.resolve(
		__dirname,
		'../../nexus-console/node_modules/@playwright/test',
	),
)
const baseURL =
	process.env.NEXUS_MOBILE_TEST_BASE_URL || 'http://127.0.0.1:5186/'
const output =
	process.env.NEXUS_MOBILE_TEST_ARTIFACT_DIR ||
	path.resolve(__dirname, '../.runtime/ui-components')
fs.mkdirSync(output, { recursive: true })

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
		})
		const errors = [],
			businessRequests = []
		const page = await context.newPage()
		page.on('pageerror', (error) => errors.push(error.message))
		await context.route('**/*', (route) => {
			const url = new URL(route.request().url())
			if (
				url.origin === new URL(baseURL).origin &&
				url.pathname.endsWith('.js')
			)
				return route.continue()
			if (
				/^\/(api|v1)(\/|$)/.test(url.pathname) ||
				url.port === '18086'
			) {
				businessRequests.push(url.pathname)
				return route.abort()
			}
			if (url.pathname.endsWith('/login-planet/scene.js'))
				return route.abort()
			return route.continue()
		})
		await page.goto(baseURL + '#/pages/component-preview/component-preview')
		await expect(
			page.getByText('Nexus 组件预览', { exact: true }).last(),
		).toBeVisible()
		const button = (text) =>
			page
				.locator('.nexus-button')
				.filter({ hasText: new RegExp('^' + text + '$') })
		await button('主操作').click()
		await expect(page.locator('.preview-caption').first()).toContainText(
			'1 次',
		)
		await button('主操作').focus()
		await page.keyboard.press('Enter')
		await expect(page.locator('.preview-caption').first()).toContainText(
			'2 次',
		)
		await page.keyboard.press('Space')
		await expect(page.locator('.preview-caption').first()).toContainText(
			'3 次',
		)
		assert.equal(
			await button('主操作').evaluate(
				(node) => getComputedStyle(node).outlineStyle,
			),
			'solid',
		)
		await button('不可用').dispatchEvent('click')
		await button('处理中').dispatchEvent('click')
		await expect(page.locator('.preview-caption').first()).toContainText(
			'3 次',
		)
		const touchTargets = await page
			.locator('.nexus-button')
			.evaluateAll((nodes) =>
				nodes.map((node) => ({
					width: node.getBoundingClientRect().width,
					height: node.getBoundingClientRect().height,
				})),
			)
		assert(
			touchTargets.every((size) => size.width >= 44 && size.height >= 44),
			'Every button needs a 44px touch target',
		)
		const input = page.locator('#preview-name input')
		await input.fill('我的移动应用')
		await expect(page.locator('.preview-value')).toContainText(
			'我的移动应用',
		)
		await button('清空').click()
		await expect(input).toHaveValue('')
		await expect(page.locator('.preview-value')).toContainText('（空）')
		await input.focus()
		await input.blur()
		await expect(page.locator('.nexus-field__message--error')).toHaveText(
			'请输入名称',
		)
		await input.fill('已修正')
		await expect(page.locator('.nexus-field__message--error')).toHaveCount(
			0,
		)
		await expect(page.locator('#preview-disabled input')).toBeDisabled()
		const colors = await page.evaluate(() => ({
			primary: getComputedStyle(
				document.querySelector('.nexus-button--primary'),
			).backgroundColor,
			success: getComputedStyle(
				document.querySelector('.nexus-badge--success'),
			).color,
			warning: getComputedStyle(
				document.querySelector('.nexus-badge--warning'),
			).color,
			danger: getComputedStyle(
				document.querySelector('.nexus-badge--danger'),
			).color,
		}))
		assert.equal(colors.primary, 'rgb(36, 123, 112)')
		assert.equal(
			new Set(Object.values(colors)).size,
			4,
			'Action, success, disabled-state warning and danger need independent colors',
		)
		await page.screenshot({
			path: output + '/components-390.png',
			fullPage: true,
		})
		await page.setViewportSize({ width: 320, height: 740 })
		assert(
			await page.evaluate(
				() => document.documentElement.scrollWidth <= innerWidth,
			),
			'Preview should not overflow a narrow phone',
		)
		await page.screenshot({
			path: output + '/components-320.png',
			fullPage: true,
		})
		console.log(
			'Component preview: v-model, parent clear, blur validation, disabled/loading, semantic colors and 44px targets passed',
		)

		await page.goto(baseURL + '#/pages/login/login')
		await expect(
			page.locator('.login-page .nexus-button--block'),
		).toHaveText('登录')
		await page.locator('.login-page .nexus-button--block').click()
		await expect(page.locator('.nexus-field__message--error')).toHaveCount(
			2,
		)
		await page.locator('#login-email input').fill('reader@example.test')
		await page.locator('#login-email input').blur()
		await expect(page.locator('.nexus-field__message--error')).toHaveCount(
			1,
		)
		await page.locator('#login-password input').fill('preview-only')
		await expect(page.locator('#login-password input')).toHaveAttribute(
			'type',
			'password',
		)
		await page.locator('.nexus-button[aria-label="显示密码"]').click()
		await expect(page.locator('#login-password input')).toHaveAttribute(
			'type',
			'text',
		)
		await page.locator('.nexus-button[aria-label="隐藏密码"]').click()
		await expect(page.locator('#login-password input')).toHaveAttribute(
			'type',
			'password',
		)
		await page.locator('#login-password input').fill('')
		await page.screenshot({
			path: output + '/login-320.png',
			fullPage: true,
		})
		console.log(
			'Login integration: page validation and password suffix action passed',
		)

		await page.goto(baseURL + '#/pages/applications/applications')
		await expect(page.locator('.applications-page')).toBeVisible()
		// Render API-shaped fixtures at the page boundary; no auth or backend is simulated as accepted.
		await page.evaluate(() => {
			const vm = getCurrentPages().at(-1).$vm
			vm.load = () => {
				vm.retryObserved = true
			}
			vm.error = ''
			vm.loading = false
			vm.total = 21
			vm.items = [
				{
					id: 1,
					name: '已启用的应用',
					status: 0,
					quota: {
						usedCount: 7,
						quotaLimit: 100,
						restCount: 93,
						periodStart: '2026-11-18T00:00:00Z',
						periodEnd: '2026-12-18T00:00:00Z',
					},
				},
				{ id: 2, name: '已禁用的应用', status: 1 },
				{ id: 3, name: '很长的应用名称'.repeat(10), status: 99 },
			]
		})
		await expect(page.locator('.nexus-badge--success')).toHaveText('已启用')
		await expect(page.locator('.nexus-badge--warning')).toHaveText('已禁用')
		await expect(page.locator('.nexus-badge--neutral')).toHaveText(
			'状态未知',
		)
		await expect(page.locator('.quota-amount')).toHaveText('7 / 100 次')
		assert.notEqual(await button('上一页').getAttribute('disabled'), null)
		await button('下一页').click()
		assert.equal(
			await page.evaluate(() => getCurrentPages().at(-1).$vm.current),
			2,
		)
		assert(
			await page.evaluate(
				() => document.documentElement.scrollWidth <= innerWidth,
			),
			'Long application names should wrap',
		)
		await page.screenshot({
			path: output + '/applications-320.png',
			fullPage: true,
		})
		await page.evaluate(() => {
			const vm = getCurrentPages().at(-1).$vm
			vm.items = []
			vm.total = 0
			vm.loading = true
		})
		await expect(page.locator('.nexus-state--loading')).toBeVisible()
		await page.evaluate(() => {
			getCurrentPages().at(-1).$vm.loading = false
		})
		await expect(page.locator('.nexus-state--empty')).toBeVisible()
		await page.evaluate(() => {
			const vm = getCurrentPages().at(-1).$vm
			vm.error = '连接暂时不可用，请重试。'
			vm.retryObserved = false
		})
		await expect(page.locator('.nexus-state--error')).toContainText(
			'连接暂时不可用',
		)
		await button('重试').click()
		assert.equal(
			await page.evaluate(
				() => getCurrentPages().at(-1).$vm.retryObserved,
			),
			true,
		)
		console.log(
			'Application integration: status 0/1/unknown, quota, pagination, loading/empty/error and retry passed',
		)
		assert.deepEqual(businessRequests, [])
		assert.deepEqual(errors, [])
		console.log('No business API calls or page runtime errors')
	} finally {
		await browser.close()
	}
}
main().catch((error) => {
	console.error(error.stack)
	process.exitCode = 1
})

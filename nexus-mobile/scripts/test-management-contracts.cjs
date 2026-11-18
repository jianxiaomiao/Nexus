const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const uri = (source) =>
	'data:text/javascript;base64,' + Buffer.from(source).toString('base64')
const read = (file) =>
	fs.readFileSync(path.resolve(__dirname, '..', file), 'utf8')
const session = uri(read('auth/session.js'))
const transport = import(
	uri(
		read('api/transport.js').replace(
			"'../auth/session.js'",
			JSON.stringify(session),
		),
	)
)
const time = import(uri(read('utils/time.js')))
async function fixture(response) {
	const { createTransport } = await transport
	let call
	const request = createTransport({
		origin: 'http://127.0.0.1:18086',
		send: (options) => {
			call = options
			options.success(response)
		},
	})
	return {
		request,
		get call() {
			return call
		},
	}
}
test('registration accepts its 201 contract and sends no Bearer', async () => {
	const f = await fixture({
		statusCode: 201,
		data: { code: 'SUCCESS', data: { id: 1 } },
	})
	assert.deepEqual(
		await f.request('/api/auth/register', {
			method: 'POST',
			body: { email: 'test@example.test' },
		}),
		{ id: 1 },
	)
	assert.equal(f.call.header.Authorization, undefined)
})
test('auth transport does not mistake an unexpected success status for login', async () => {
	const f = await fixture({
		statusCode: 201,
		data: { code: 'SUCCESS', data: {} },
	})
	await assert.rejects(f.request('/api/auth/login'), {
		code: 'REQUEST_UNAVAILABLE',
	})
})
test('business failure preserves only a recognized code and status', async () => {
	const f = await fixture({
		statusCode: 409,
		data: {
			code: 'APPLICATION_NAME_ALREADY_EXISTS',
			message: 'private response body',
		},
	})
	await assert.rejects(
		f.request('/api/application/create'),
		(e) =>
			e.code === 'APPLICATION_NAME_ALREADY_EXISTS' &&
			e.status === 409 &&
			!e.message.includes('private'),
	)
})
test('known authentication failures reach the session boundary unchanged', async () => {
	const f = await fixture({
		statusCode: 401,
		data: { code: 'INVALID_ACCESS_TOKEN' },
	})
	await assert.rejects(f.request('/api/application'), {
		code: 'INVALID_ACCESS_TOKEN',
	})
})
test('unknown server payload does not leak an arbitrary message into the UI', async () => {
	const f = await fixture({
		statusCode: 500,
		data: { code: 'UnexpectedSecretValue', message: 'private response' },
	})
	await assert.rejects(f.request('/api/application'), {
		code: 'REQUEST_UNAVAILABLE',
		status: 500,
	})
})
test('missing data field is not a successful empty mutation', async () => {
	const f = await fixture({ statusCode: 200, data: { code: 'SUCCESS' } })
	await assert.rejects(f.request('/api/apiKey', { method: 'DELETE' }), {
		code: 'REQUEST_UNAVAILABLE',
	})
})
test('untrusted origin is rejected before sending credentials', async () => {
	const { createTransport } = await transport
	let sent = false
	const request = createTransport({
		origin: 'http://elsewhere.test',
		send: () => {
			sent = true
		},
	})
	await assert.rejects(request('/api/auth/login'), {
		code: 'INVALID_BACKEND_ORIGIN',
	})
	assert.equal(sent, false)
})
test('network diagnostics are replaced with a fixed safe error', async () => {
	const { createTransport } = await transport
	const request = createTransport({
		origin: 'http://127.0.0.1:18086',
		send: (options) =>
			options.fail({ errMsg: 'private url or credential' }),
	})
	await assert.rejects(request('/api/application'), {
		code: 'NETWORK_UNAVAILABLE',
	})
})
test('UTC, explicit offsets and legacy Beijing strings display the same instant', async () => {
	const { beijingTime } = await time
	for (const value of [
		'2026-11-18T00:00:00Z',
		'2026-11-18T08:00:00+08:00',
		'2026-11-17T19:00:00-05:00',
		'2026-11-18T08:00:00',
	])
		assert.equal(beijingTime(value), '2026-11-18 08:00')
	assert.equal(beijingTime(undefined), '—')
})
test('custom range rejects invalid calendar dates, reversed and over-90-day periods', async () => {
	const { customRange } = await time
	assert.ok(customRange('2026-02-30T00:00', '2026-03-01T00:00'))
	assert.ok(customRange('2026-11-18T00:00', '2026-11-18T00:00'))
	assert.ok(customRange('2026-01-01T00:00', '2026-04-02T00:00'))
	assert.equal(customRange('2026-01-01T00:00', '2026-04-01T00:00'), '')
})

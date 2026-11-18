// AES-GCM uses a non-exportable Android Keystore key. No plaintext storage fallback.
export function createAndroidVault(origin) {
	if (typeof plus === 'undefined' || plus.os.name !== 'Android')
		throw new Error('UNSUPPORTED_PLATFORM')
	const android = plus.android
	const call = (object, method, ...args) =>
		android.invoke(object, method, ...args)
	const instance = (name, ...args) => {
		const value = android.newObject(name, ...args)
		if (!value) throw new Error('SECURE_STORAGE_UNAVAILABLE')
		return value
	}
	const appId = String(plus.runtime.appid || 'nexus-mobile')
	const alias = 'nexus.mobile.session.v1.' + appId
	const prefs = call(
		android.runtimeMainActivity(),
		'getSharedPreferences',
		alias,
		0,
	)
	const keyStore = call(
		'java.security.KeyStore',
		'getInstance',
		'AndroidKeyStore',
	)
	call(keyStore, 'load', null)
	const bytes = (text) =>
		call(instance('java.lang.String', text), 'getBytes', 'UTF-8')
	const aad = bytes(alias + '|' + origin)
	const encode = (value) =>
		String(call('android.util.Base64', 'encodeToString', value, 2))
	const decode = (value) => call('android.util.Base64', 'decode', value, 2)

	function key() {
		if (!call(keyStore, 'containsAlias', alias)) {
			const builder = instance(
				'android.security.keystore.KeyGenParameterSpec$Builder',
				alias,
				3,
			)
			call(builder, 'setBlockModes', ['GCM'])
			call(builder, 'setEncryptionPaddings', ['NoPadding'])
			call(builder, 'setKeySize', 256)
			const generator = call(
				'javax.crypto.KeyGenerator',
				'getInstance',
				'AES',
				'AndroidKeyStore',
			)
			call(generator, 'init', call(builder, 'build'))
			if (!call(generator, 'generateKey'))
				throw new Error('SECURE_STORAGE_UNAVAILABLE')
		}
		return call(keyStore, 'getKey', alias, null)
	}
	return {
		read() {
			const raw = call(prefs, 'getString', origin, null)
			if (raw === null) return null
			const envelope = JSON.parse(String(raw))
			if (
				envelope.version !== 1 ||
				typeof envelope.iv !== 'string' ||
				typeof envelope.body !== 'string' ||
				envelope.iv.length > 64 ||
				envelope.body.length > 16384
			)
				throw new Error('SECURE_STORAGE_UNAVAILABLE')
			const cipher = call(
				'javax.crypto.Cipher',
				'getInstance',
				'AES/GCM/NoPadding',
			)
			call(
				cipher,
				'init',
				2,
				key(),
				instance(
					'javax.crypto.spec.GCMParameterSpec',
					128,
					decode(envelope.iv),
				),
			)
			call(cipher, 'updateAAD', aad)
			const plaintext = call(cipher, 'doFinal', decode(envelope.body))
			return JSON.parse(
				String(
					call(
						instance('java.lang.String', plaintext, 'UTF-8'),
						'toString',
					),
				),
			)
		},
		write(record) {
			const cipher = call(
				'javax.crypto.Cipher',
				'getInstance',
				'AES/GCM/NoPadding',
			)
			call(cipher, 'init', 1, key())
			call(cipher, 'updateAAD', aad)
			const body = encode(
				call(cipher, 'doFinal', bytes(JSON.stringify(record))),
			)
			const envelope = JSON.stringify({
				version: 1,
				iv: encode(call(cipher, 'getIV')),
				body,
			})
			const editor = call(prefs, 'edit')
			call(editor, 'putString', origin, envelope)
			if (call(editor, 'commit') !== true)
				throw new Error('SECURE_STORAGE_UNAVAILABLE')
		},
	}
}

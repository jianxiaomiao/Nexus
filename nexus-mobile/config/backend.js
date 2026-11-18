// Local test backend. adb reverse tcp:18086 tcp:18086 connects the Android loopback.
// Release builds must replace this with the intended public HTTPS origin.
export const API_ORIGIN = 'http://127.0.0.1:18086'

package dev.kalimote.app

import dev.kalimote.atvremote.ClientIdentity
import dev.kalimote.atvremote.DeviceInfo
import dev.kalimote.atvremote.FireTvClient
import dev.kalimote.atvremote.RemoteClient
import dev.kalimote.atvremote.RemoteState
import dev.kalimote.atvremote.TvClient

/** Creates the right kind of connection for a TV. */
fun createTvClient(
    device: TvDevice,
    identity: ClientIdentity,
    deviceInfo: DeviceInfo,
    listener: (RemoteState) -> Unit,
): TvClient =
    if (device.isFireTv) FireTvClient(device.host, identity, listener = listener)
    else RemoteClient(device.host, identity, deviceInfo, listener = listener)

package dev.droidtop.pluginhost;

/**
 * The plugin to host broker (docs/plugin-api.md 1.4). :app hands ONE object
 * of this interface to each plugin it loads; every call a plugin makes
 * beyond its own process goes through it, and the caller is known from which
 * object was called, never from anything in the request.
 *
 * requestJson is {"api", "version", "op", "args"}; the return value is
 * {"ok":true,"data":{...}} or {"ok":false,"error":{"code","message"}} with
 * one of the closed error codes. It never throws across the binder.
 */
interface IPluginHostBroker {
    String call(String requestJson);
}

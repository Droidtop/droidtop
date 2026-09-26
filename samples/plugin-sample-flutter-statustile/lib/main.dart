// droidtop flutter_embed sample plugin (docs/SPEC.md 12a). No UI, no
// runApp(): FlutterDroidtopPlugin hosts this engine headless and only
// ever talks to it over the one MethodChannel below -- the same
// "JSON in, JSON out, no rendering surface required" shape a status_tile
// capability needs. The channel name is fixed to this plugin's own id
// (manifest.template.json's "id"); FlutterDroidtopPlugin constructs it
// from the installed plugin's id the same way for every flutter_embed
// plugin, so this string must match that id exactly.
import 'dart:convert';
import 'dart:io';

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

const MethodChannel _channel = MethodChannel(
  'dev.droidtop.pluginhost/droidtop.sample-flutter-statustile',
);

void main() {
  // Only the services/widgets binding is needed to get a
  // BinaryMessenger -- there is no view to attach and no runApp() call,
  // deliberately: this plugin never renders anything, it only answers
  // capability calls.
  WidgetsFlutterBinding.ensureInitialized();
  _channel.setMethodCallHandler(_handleCall);
}

Future<String> _handleCall(MethodCall call) async {
  if (call.method != 'invoke') {
    return jsonEncode({'ok': false, 'error': 'unknown method ${call.method}'});
  }
  try {
    final payload = jsonDecode(call.arguments as String) as Map<String, dynamic>;
    final capability = payload['capability'] as String?;
    if (capability != 'status_tile') {
      return jsonEncode({'ok': false, 'error': 'unsupported capability $capability'});
    }
    final args = (payload['args'] as Map<String, dynamic>?) ?? const {};
    // The one deliberate way to test crash containment on the rig
    // (dq-flutterembed-01), same shape plugin.py's own "force-crash"
    // query already gives the python leg -- never present in a real
    // droidtop call site. A plain thrown Dart exception here would only
    // produce a MethodChannel error reply (Flutter's own dispatcher
    // catches it) -- it would NOT crash :pluginhost, so it would not
    // actually exercise PluginCrashPolicy the way the python/native
    // samples' forced crashes do. exit() kills this process outright,
    // which does.
    if (args['query'] == 'force-crash') {
      exit(1);
    }
    return jsonEncode({
      'ok': true,
      'values': {
        'label': 'Flutter sample',
        'value': 'Hello from Dart, running inside :pluginhost (flutter_embed)',
      },
    });
  } catch (e) {
    return jsonEncode({'ok': false, 'error': e.toString()});
  }
}

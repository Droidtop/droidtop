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

String _rememberedName = '';

void main() {
  // Only the services/widgets binding is needed to get a
  // BinaryMessenger -- there is no view to attach and no runApp() call,
  // deliberately: this plugin never renders anything, it only answers
  // capability calls.
  WidgetsFlutterBinding.ensureInitialized();
  _channel.setMethodCallHandler(_handleCall);
  // The flutter_embed readiness handshake (docs/SPEC.md 12a, required of
  // every flutter_embed plugin, not just this sample): FlutterDroidtopPlugin's
  // onLoad() blocks waiting for exactly this call before returning, because
  // executeDartEntrypoint() starting this isolate is not the same moment as
  // this line actually running -- a call posted right after onLoad used to
  // race this isolate's own startup and fail with a channel-not-yet-registered
  // PlatformException. No sleep, no polling: this is the signal.
  _channel.invokeMethod('ready');
}

Future<String> _handleCall(MethodCall call) async {
  try {
    if (call.method == 'handle') {
      return _handleContract2(call);
    }
    if (call.method == 'invoke') {
      return _handleInvoke(call);
    }
    return jsonEncode({'ok': false, 'error': 'unknown method ${call.method}'});
  } catch (e) {
    return jsonEncode({'ok': false, 'error': e.toString()});
  }
}

String _handleContract2(MethodCall call) {
  final envelope = jsonDecode(call.arguments as String) as Map<String, dynamic>;
  final point = envelope['point'] as String?;
  final op = envelope['op'] as String?;

  if (point == 'ui.status_tile' && op == 'state') {
    return jsonEncode({
      'ok': true,
      'data': {
        'label': 'Sample tile (Flutter)',
        'value': 'Hello from Dart',
      },
    });
  }

  if (point == 'ui.settings') {
    if (op == 'view') {
      return jsonEncode({
        'ok': true,
        'data': {
          'view': 1,
          'sections': [
            {
              'id': 'main',
              'items': [
                {
                  'type': 'info',
                  'id': 'about',
                  'title': 'This is a sample plugin page',
                  'subtitle': 'Drawn by droidtop from data the plugin returned',
                },
                {
                  'type': 'text',
                  'id': 'name',
                  'title': 'Your name',
                  'value': _rememberedName,
                },
                {
                  'type': 'button',
                  'id': 'greet',
                  'title': 'Greet me',
                  'action': {
                    'kind': 'call',
                    'op': 'greet',
                  },
                },
              ],
            },
          ],
        },
      });
    }
    if (op == 'greet') {
      final args = (envelope['args'] as Map<String, dynamic>?) ?? {};
      final values = (args['values'] as Map<String, dynamic>?) ?? {};
      final name = (values['name'] as String?) ?? 'there';
      _rememberedName = name.isNotEmpty ? name : _rememberedName;
      return jsonEncode({
        'ok': true,
        'data': {
          'message': 'Hello, $name',
        },
      });
    }
  }

  return jsonEncode({
    'ok': false,
    'error': {
      'code': 'UNSUPPORTED',
      'message': 'unsupported point $point op $op',
    },
  });
}

String _handleInvoke(MethodCall call) {
  final payload = jsonDecode(call.arguments as String) as Map<String, dynamic>;
  final capability = payload['capability'] as String?;
  if (capability != 'status_tile') {
    return jsonEncode({'ok': false, 'error': 'unsupported capability $capability'});
  }
  final args = (payload['args'] as Map<String, dynamic>?) ?? const {};
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
}

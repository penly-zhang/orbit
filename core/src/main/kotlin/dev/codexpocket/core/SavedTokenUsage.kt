package dev.codexpocket.core

import java.util.Base64

/** Only emits counters from this thread; conversation content stays on its computer. */
internal fun savedTokenUsageCommand(path: String, threadId: String): String {
    val script = """
import json, os, signal, sys
signal.alarm(3)
path, thread_id = sys.argv[1:3]
limit = 16 * 1024 * 1024
try:
    with open(path, 'rb') as source:
        first = json.loads(source.readline(1024 * 1024))
        if first.get('type') != 'session_meta' or first.get('payload', {}).get('id') != thread_id:
            print(json.dumps({'error': 'session_mismatch'}))
            sys.exit(0)
        size = os.fstat(source.fileno()).st_size
        offset = max(0, size - limit)
        source.seek(offset)
        if offset:
            source.readline(limit)
        records = source.read(limit).splitlines()
    for line in reversed(records):
        if b'"token_count"' not in line or len(line) > 1024 * 1024:
            continue
        try:
            record = json.loads(line)
            payload = record.get('payload', {})
            info = payload.get('info')
            if record.get('type') != 'event_msg' or payload.get('type') != 'token_count' or not isinstance(info, dict):
                continue
            last = info.get('last_token_usage', {}).get('total_tokens')
            total = info.get('total_token_usage', {}).get('total_tokens')
            if type(last) is not int or type(total) is not int or last < 0 or total < 0:
                continue
            window = info.get('model_context_window')
            if type(window) is not int or window <= 0:
                window = None
            print(json.dumps({'last': {'totalTokens': last}, 'total': {'totalTokens': total}, 'modelContextWindow': window}))
            sys.exit(0)
        except (ValueError, TypeError, AttributeError):
            continue
    print(json.dumps({'error': 'scan_limit'} if offset else {}))
except (OSError, ValueError, TypeError, AttributeError):
    print(json.dumps({'error': 'unreadable'}))
""".trimIndent()
    val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_8))
    val bootstrap = "import base64;exec(base64.b64decode('$encoded'))"
    return pythonCommand(listOf("-u", "-c", bootstrap, path, threadId))
}

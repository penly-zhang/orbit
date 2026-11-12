"""Protocol fixture carried inside an actual loopback SSH exec channel, no model calls."""
import copy
import base64
import hashlib
import json
import os
import struct
import sys
import time

source = sys.stdin.buffer
sink = sys.stdout.buffer
headers = {}
while True:
    line = source.readline()
    if line in (b"\r\n", b"\n", b""):
        break
    if b":" in line:
        key, value = line.decode().split(":", 1)
        headers[key.lower()] = value.strip()
accept = base64.b64encode(hashlib.sha1((headers["sec-websocket-key"] + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
sink.write(f"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: {accept}\r\n\r\n".encode())
sink.flush()

def exact(n):
    data = b""
    while len(data) < n:
        part = source.read(n - len(data))
        if not part:
            raise EOFError()
        data += part
    return data

def frame(payload, opcode=1, final=True):
    n = len(payload)
    head = bytes([(128 if final else 0) | opcode])
    head += bytes([n]) if n < 126 else b"\x7e" + struct.pack("!H", n) if n <= 65535 else b"\x7f" + struct.pack("!Q", n)
    sink.write(head + payload)
    sink.flush()

def send(message):
    payload = json.dumps(message, ensure_ascii=False).encode()
    if len(payload) > 10000:
        frame(payload[:113], final=False)
        frame(b"keepalive", opcode=9)
        frame(payload[113:], opcode=0)
    else:
        frame(payload)

approval_request_id = None
threads = {}
loaded_threads = set()
list_attempts = {}
thread_number = 0
stats = {"historyBeforeFirst": 0, "resumeBeforeFirst": 0, "firstMessages": 0, "resumeCount": 0}
models = [
    {"model": "fixture-smart", "displayName": "Fixture Smart", "description": "Test model", "isDefault": True, "defaultReasoningEffort": "low", "supportedReasoningEfforts": [{"reasoningEffort": "low", "description": "Fast"}, {"reasoningEffort": "high", "description": "Deep"}]},
    {"model": "fixture-small", "displayName": "Fixture Small", "description": "Limited efforts", "isDefault": False, "defaultReasoningEffort": "medium", "supportedReasoningEfforts": [{"reasoningEffort": "medium", "description": "Balanced"}]},
]
def resumed(thread):
    settings = dict(thread["settings"])
    settings["sandbox"] = settings.pop("sandboxPolicy")
    settings["reasoningEffort"] = settings.pop("effort")
    return dict(settings, thread={k: v for k, v in thread.items() if k not in ["settings", "materialized", "projectId"]})
def history_thread(thread_id):
    if thread_id.startswith("fixture-saved-"):
        return {"id": thread_id, "cwd": "/fixture/project", "name": "Saved usage", "materialized": True,
                "settings": {"model": "fixture-smart", "effort": "low", "approvalPolicy": "on-request", "sandboxPolicy": {"type": "workspaceWrite"}},
                "turns": [] if thread_id == "fixture-saved-empty-tab" else [{"id": "saved-turn", "status": "completed", "items": [{"id": "saved-user", "type": "userMessage", "content": [{"type": "text", "text": "saved history"}]}]}]}
    if thread_id == "fixture-paged":
        return {"materialized": True, "turns": [{"id": "paged-turn", "status": "completed", "startedAt": 100, "completedAt": 103, "durationMs": 3000,
            "items": [{"id": "paged-agent-" + str(i), "type": "agentMessage", "text": "Message " + str(i), "phase": "final_answer"} for i in range(80)]}]}
    return threads.get(thread_id)
try:
    while True:
        first, second = exact(2)
        n = second & 127
        if n == 126:
            n = struct.unpack("!H", exact(2))[0]
        elif n == 127:
            n = struct.unpack("!Q", exact(8))[0]
        assert second & 128, "Client frames must be masked"
        mask = exact(4)
        payload = bytes(v ^ mask[i % 4] for i, v in enumerate(exact(n)))
        if first & 15 == 10:
            continue
        message = json.loads(payload)
        if message.get("id") == "approval-string-id":
            send({"id": approval_request_id, "result": message["result"]})
            continue
        if message.get("id") == "shared-approval":
            log = os.environ.get("POCKET_FIXTURE_APPROVAL_LOG")
            if log:
                with open(log, "a") as target:
                    target.write(message["result"]["decision"] + "\n")
            send({"method": "serverRequest/resolved", "params": {"requestId": "shared-approval"}})
            continue
        method = message.get("method")
        if method == "initialized":
            if os.environ.get("POCKET_FIXTURE_APPROVAL_LOG"):
                send({"id": "shared-approval", "method": "item/commandExecution/requestApproval", "params": {"threadId": "shared-thread", "command": "fixture approval only"}})
            continue
        params = message.get("params", {})
        if method == "initialize":
            result = {"userAgent": "fixture"}
        elif method == "skills/list":
            result = {"data": [{"cwd": params["cwds"][0], "errors": [], "skills": [{"name": "fixture-skill", "path": "/fixture/SKILL.md", "description": "Fixture skill", "enabled": True, "scope": "user", "pluginId": "fixture-plugin"}]}]}
        elif method == "mcpServerStatus/list":
            result = {"data": [{"name": "fixture-mcp", "runtimeStatus": "connected", "authStatus": "oAuth", "tools": {"read_log": {"description": "Read fixture log"}}, "pluginId": "fixture-plugin"}], "nextCursor": None}
        elif method == "plugin/list":
            result = {"marketplaces": [{"name": "fixture", "plugins": [{"id": "fixture-plugin", "name": "Fixture plugin", "installed": True, "enabled": True, "interface": {"shortDescription": "Fixture tools"}}]}]}
        elif method == "probe/echo":
            result = params
        elif method == "probe/approval":
            approval_request_id = message["id"]
            send({"id": "approval-string-id", "method": "item/commandExecution/requestApproval", "params": {"threadId": "fixture-thread", "command": "test command"}})
            continue
        elif method == "probe/stats":
            result = stats
        elif method == "probe/completeTurn":
            thread = threads[params["threadId"]]
            turn = thread["turns"][-1]
            turn["status"] = "completed"
            send({"method": "turn/completed", "params": {"threadId": thread["id"], "turn": turn}})
            result = {}
        elif method == "model/list":
            index = 1 if params.get("cursor") == "model-page-2" else 0
            result = {"data": [models[index]], "nextCursor": "model-page-2" if index == 0 else None}
        elif method == "collaborationMode/list":
            result = {"data": [{"name": "Default", "mode": "default"}, {"name": "Plan", "mode": "plan"}]}
        elif method == "thread/name/set":
            thread = threads[params["threadId"]]
            thread["name"] = params["name"]
            send({"method": "thread/name/updated", "params": {"threadId": thread["id"], "threadName": thread["name"]}})
            result = {}
        elif method == "thread/compact/start":
            assert params["threadId"] in threads
            stats["compactions"] = stats.get("compactions", 0) + 1
            result = {}
        elif method == "thread/fork":
            origin_thread = threads[params["threadId"]]
            assert params["excludeTurns"] is True
            thread_number += 1
            thread_id = "fixture-new-" + str(thread_number)
            threads[thread_id] = copy.deepcopy(origin_thread)
            threads[thread_id]["id"] = thread_id
            result = resumed(threads[thread_id])
        elif method == "configRequirements/read":
            result = {"requirements": None}
        elif method == "permissionProfile/list":
            result = {"data": [{"id": key, "description": None, "allowed": key != "blocked"} for key in [":read-only", ":workspace", ":danger-full-access", "blocked"]], "nextCursor": None}
        elif method == "thread/start":
            assert params.get("sandbox", "workspace-write") in ["read-only", "workspace-write", "danger-full-access"]
            thread_number += 1
            thread_id = "fixture-new-" + str(thread_number)
            threads[thread_id] = {"id": thread_id, "cwd": params["cwd"], "projectId": params.get("projectId"), "name": None, "materialized": False, "turns": []}
            threads[thread_id]["settings"] = {"model": "fixture-smart", "effort": "low", "approvalPolicy": params.get("approvalPolicy", "on-request"), "approvalsReviewer": "user", "sandboxPolicy": {"type": "readOnly" if params.get("sandbox") == "read-only" else "workspaceWrite"}, "activePermissionProfile": {"id": ":read-only" if params.get("sandbox") == "read-only" else ":workspace"}, "collaborationMode": {"mode": "default", "settings": {"model": "fixture-smart", "reasoning_effort": "low", "developer_instructions": "Preserve these instructions"}}}
            result = resumed(threads[thread_id])
        elif method == "thread/settings/update":
            thread = threads[params["threadId"]]
            patch = {k: v for k, v in params.items() if k != "threadId"}
            assert not ("permissions" in patch and "sandboxPolicy" in patch)
            if patch.get("permissions") == "blocked":
                send({"id": message["id"], "error": {"code": -32000, "message": "permission profile is blocked"}})
                continue
            updated = dict(thread["settings"], **patch)
            model = next(m for m in models if m["model"] == updated["model"])
            if updated["effort"] not in [e["reasoningEffort"] for e in model["supportedReasoningEfforts"]]:
                send({"id": message["id"], "error": {"code": -32602, "message": "unsupported effort"}})
                continue
            if "model" in patch:
                assert updated["collaborationMode"]["settings"]["model"] == updated["model"]
                assert updated["collaborationMode"]["settings"]["reasoning_effort"] == updated["effort"]
            if "permissions" in patch:
                key = updated.pop("permissions")
                updated["activePermissionProfile"] = {"id": key}
                updated["sandboxPolicy"] = {"type": {":workspace": "workspaceWrite", ":read-only": "readOnly", ":danger-full-access": "dangerFullAccess"}[key]}
            thread["settings"] = updated
            send({"method": "thread/settings/updated", "params": {"threadId": thread["id"], "threadSettings": updated}})
            result = {}
        elif method in ["thread/archive", "thread/unarchive"]:
            thread = threads[params["threadId"]]
            assert thread["materialized"]
            thread["archived"] = method == "thread/archive"
            send({"method": "thread/archived" if thread["archived"] else "thread/unarchived", "params": {"threadId": thread["id"]}})
            result = {} if thread["archived"] else {"thread": thread}
        elif method == "thread/list":
            stats["lastThreadListParams"] = params
            if os.environ.get("POCKET_FIXTURE_LIST_DELAY"):
                with open(os.path.join(os.environ["CODEX_POCKET_UPLOAD_ROOT"], "list-requests.jsonl"), "a") as log:
                    log.write(json.dumps(params) + "\n")
                time.sleep(float(os.environ["POCKET_FIXTURE_LIST_DELAY"]))
            key = (params.get("projectId"), params.get("archived", False))
            list_attempts[key] = list_attempts.get(key, 0) + 1
            if key[0] == "fixture-list-retry" and list_attempts[key] == 1:
                send({"id": message["id"], "error": {"code": -32000, "message": "Temporary list failure"}})
                continue
            data = []
            if "appServer" in params.get("sourceKinds", []):
                for thread in threads.values():
                    if thread["materialized"] and thread.get("archived", False) == params.get("archived", False):
                        if "projectId" in params and thread.get("projectId") != params["projectId"]:
                            continue
                        if "cwd" in params and thread["cwd"] not in params["cwd"]:
                            continue
                        data.append({k: v for k, v in thread.items() if k not in ["materialized"]})
            result = {"data": data, "nextCursor": None}
        elif method == "thread/read":
            tid = params["threadId"]
            assert params.get("includeTurns") is False
            path = None
            if tid.startswith("fixture-saved-"):
                path = os.path.join(os.environ["CODEX_POCKET_UPLOAD_ROOT"], tid + ".jsonl")
                with open(path, "w") as target:
                    target.write(json.dumps({"type": "session_meta", "payload": {"id": "other-thread" if tid.endswith("mismatch") else tid}}) + "\n")
                    if not tid.endswith("empty"):
                        target.write(json.dumps({"type": "event_msg", "payload": {"type": "token_count", "info": {"last_token_usage": {"total_tokens": 150}, "total_token_usage": {"total_tokens": 1500}, "model_context_window": 1000}}}) + "\n")
                        target.write(json.dumps({"type": "event_msg", "payload": {"type": "token_count", "info": None}}) + "\n")
                        target.write('{"type":"event_msg","payload":')
                if tid.endswith("race"):
                    send({"method": "thread/tokenUsage/updated", "params": {"threadId": tid, "tokenUsage": {"last": {"totalTokens": 900}, "total": {"totalTokens": 9000}, "modelContextWindow": 1000}}})
            result = {"thread": {"id": tid, "path": path}}
        elif method in ["thread/turns/list", "thread/items/list", "thread/resume"]:
            thread_id = params["threadId"]
            if method == "thread/resume" and thread_id == "fixture-writer-conflict":
                send({"id": message["id"], "error": {"code": -32000, "message": "Failed to initialize session: thread-store conflict: thread fixture-writer-conflict already has an active writer: process TRAE CLI"}})
                continue
            if thread_id in ["fixture-missing-rollout", "fixture-missing-empty"]:
                send({"id": message["id"], "error": {"code": -32600, "message": "no rollout found for thread id " + thread_id}})
                continue
            if thread_id == "fixture-history-denied":
                send({"id": message["id"], "error": {"code": -32000, "message": "permission denied"}})
                continue
            if thread_id == "fixture-saved-await-attach":
                if method == "thread/resume":
                    loaded_threads.add(thread_id)
                elif thread_id not in loaded_threads:
                    send({"id": message["id"], "error": {"code": -32600, "message": "thread not loaded: " + thread_id}})
                    continue
            thread = history_thread(thread_id)
            if not thread or not thread["materialized"]:
                stats["historyBeforeFirst" if method != "thread/resume" else "resumeBeforeFirst"] += 1
                send({"id": message["id"], "error": {"code": -32600, "message": "thread " + thread_id + " is not materialized yet; " + method + " is unavailable before first user message"}})
                continue
            if method == "thread/items/list":
                records = [{"turnId": t["id"], "item": item, "startedAtMs": 100000, "completedAtMs": 103000} for t in thread["turns"] for item in t["items"]][::-1]
                offset = int(params.get("cursor") or 0)
                limit = params["limit"]
                assert limit <= 40
                result = {"data": records[offset:offset + limit], "nextCursor": str(offset + limit) if offset + limit < len(records) else None}
                stats["lastItemLimit"] = limit
            elif method == "thread/turns/list":
                records = [dict(t, items=[] if params.get("itemsView") == "notLoaded" else t["items"]) for t in thread["turns"]]
                result = {"data": records, "nextCursor": None}
            else:
                stats["resumeCount"] += 1
                result = resumed(thread)
        elif method in ["turn/start", "turn/steer"]:
            thread = threads[params["threadId"]]
            content = params["input"]
            text = "\n".join(part["text"] for part in content if part["type"] == "text")
            if text == "fixture-reject-images":
                send({"id": message["id"], "error": {"code": -32602, "message": "fixture rejected message"}})
                continue
            images = [part for part in content if part["type"] in ["image", "localImage"]]
            for part in images:
                if part["type"] == "localImage":
                    assert os.path.realpath(part["path"]).startswith(os.path.realpath(os.environ["CODEX_POCKET_UPLOAD_ROOT"]) + os.sep)
                    assert open(part["path"], "rb").read()
                    continue
                assert part["url"].startswith("data:image/") and ";base64," in part["url"]
                assert base64.b64decode(part["url"].split(",", 1)[1])
            assert text or images or any(part["type"] == "skill" for part in content)
            thread["materialized"] = True
            thread["preview"] = text or "图片"
            stats["firstMessages"] += 1
            stats["lastMethod"] = method
            stats.setdefault("messages", []).append({"text": text, "method": method, "input": content})
            turn_id = params.get("expectedTurnId") or "turn-" + thread["id"] + ("-" + str(stats["firstMessages"]) if text.startswith("fixture-queue") else "")
            user = {"id": "user-" + thread["id"] + ("-steer-" + str(stats["firstMessages"]) if method == "turn/steer" else ""), "type": "userMessage", "content": content}
            turn = {"id": turn_id, "status": "completed", "startedAt": 100, "completedAt": 103, "durationMs": 3000,
                    "items": [user, {"id": "answer-" + thread["id"], "type": "agentMessage", "text": "OK", "phase": "final_answer"}]}
            if text.startswith("fixture-queue"):
                turn["status"] = "inProgress"
            if method == "turn/steer":
                assert thread["turns"][-1]["id"] == turn_id
                thread["turns"][-1]["items"].append(user)
                result = {"turnId": turn_id}
            else:
                thread["turns"] = [turn]
                result = {"turn": turn}
        elif method == "project/list":
            result = {"data": [{"id": "fixture-project", "name": "Fixture", "roots": [{"path": "/fixture/project"}]}], "nextCursor": None}
        elif method == "fs/readDirectory":
            if params["path"].endswith("/unreadable"):
                send({"id": message["id"], "error": {"code": -32000, "message": "fixture directory permission denied"}})
                continue
            result = {"entries": [{"fileName": "README.md", "isDirectory": False, "isFile": True}]}
        elif method == "fs/readFile":
            upload_root = os.environ.get("CODEX_POCKET_UPLOAD_ROOT")
            if upload_root and os.path.realpath(params["path"]).startswith(os.path.realpath(upload_root) + os.sep):
                send({"id": message["id"], "result": {"dataBase64": base64.b64encode(open(params["path"], "rb").read()).decode()}})
                continue
            result = {"dataBase64": "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j4XcAAAAASUVORK5CYII=" if params["path"].endswith("image.png") else base64.b64encode("日本語と中文".encode()).decode()}
        else:
            send({"id": message["id"], "error": {"code": -32601, "message": "Unsupported fixture method"}})
            continue
        send({"id": message["id"], "result": result})
        if method == "turn/start" and text.startswith("fixture-queue"):
            send({"method": "turn/started", "params": {"threadId": thread["id"], "turn": turn}})
        if method in ["turn/start", "turn/steer"] and text in ["fixture-usage-a", "fixture-usage-b"]:
            last = 150 if text.endswith("-a") else 750
            send({"method": "thread/tokenUsage/updated", "params": {"threadId": thread["id"], "tokenUsage": {"last": {"totalTokens": last}, "total": {"totalTokens": last * 10}, "modelContextWindow": 1000}}})
except EOFError:
    pass

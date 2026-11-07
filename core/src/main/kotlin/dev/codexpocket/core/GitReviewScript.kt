package dev.codexpocket.core

import java.util.Base64
import kotlinx.serialization.encodeToString

internal fun gitReviewCommand(root: String, action: String, token: String = "", paths: List<String> = emptyList()): String {
    require(action in setOf("overview", "begin", "compare", "finish", "read"))
    val encoded = Base64.getEncoder().encodeToString(gitReviewScript.toByteArray(Charsets.UTF_8))
    val bootstrap = "import base64;exec(base64.b64decode('$encoded'))"
    val arguments = listOf(root, action, token, json.encodeToString(paths)).joinToString(" ", transform = ::shellQuote)
    return withWorkingGit(withWorkingPython("-u -c ${shellQuote(bootstrap)} \"\$pocket_candidate\" $arguments"))
}

private val gitReviewScript = """
import os, sys, json, subprocess, hashlib, stat, time, uuid, shutil, difflib, signal
signal.signal(signal.SIGALRM, lambda *_: (_ for _ in ()).throw(TimeoutError('读取超时')))
signal.alarm(12)
git, requested, action, token, extra_json = sys.argv[1:6]
root = os.path.realpath(requested)
env = dict(os.environ, GIT_OPTIONAL_LOCKS='0')
MAX_FILE, MAX_TOTAL, MAX_FILES, MAX_DIFF = 256*1024, 16*1024*1024, 2000, 1024*1024
def run(args, cwd=root, optional=False):
    p = subprocess.run([git, '--literal-pathspecs', '--no-pager', '-C', cwd] + args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env, timeout=8)
    if p.returncode and not optional:
        raise RuntimeError(p.stderr.decode('utf-8', 'replace').strip() or 'Git 读取失败')
    return p.stdout if p.returncode == 0 else b''
def text(data):
    if b'\0' in data: return None
    try: return data.decode('utf-8')
    except UnicodeDecodeError: return None
def digest(data): return hashlib.sha256(data).hexdigest()
def fingerprint(path, status_code='', index=''):
    absolute = os.path.normpath(os.path.join(root, path))
    try:
        info = os.lstat(absolute)
        if stat.S_ISLNK(info.st_mode): content = os.readlink(absolute).encode('utf-8', 'replace')
        elif stat.S_ISREG(info.st_mode):
            if info.st_size > 8*1024*1024:
                content = ('large:%s:%s' % (info.st_size, info.st_mtime_ns)).encode()
            else:
                with open(absolute, 'rb') as f: content = f.read(8*1024*1024+1)
        else: content = b'non-regular'
        value = digest(content) + ':' + str(stat.S_IMODE(info.st_mode))
    except FileNotFoundError: value = 'missing'
    return digest((value + ':' + status_code + ':' + index).encode())
def overview(extras):
    repository = run(['rev-parse', '--show-toplevel']).decode().strip()
    head = run(['rev-parse', '--verify', 'HEAD'], optional=True).decode().strip()
    branch = run(['symbolic-ref', '--quiet', '--short', 'HEAD'], optional=True).decode().strip() or (head[:8] if head else '尚无提交')
    prefix = run(['rev-parse', '--show-prefix']).decode().rstrip('\r\n')
    def local(path): return os.path.relpath(path, prefix or '.')
    indices = {}
    for record in run(['ls-files', '--stage', '-z'], cwd=repository).split(b'\0'):
        if b'\t' in record:
            meta, path = record.split(b'\t', 1)
            indices[local(path.decode('utf-8', 'replace'))] = meta.decode()
    def counts(cached):
        args = ['diff', '--no-ext-diff', '--no-textconv', '--no-renames', '--numstat', '-z']
        if cached: args.append('--cached')
        result = {}
        for record in run(args).split(b'\0'):
            parts = record.split(b'\t', 2)
            if len(parts) == 3:
                result[local(parts[2].decode('utf-8', 'replace'))] = [int(p) if p != b'-' else None for p in parts[:2]]
        return result
    staged, working = counts(True), counts(False)
    records = run(['status', '--porcelain=v1', '-z', '--untracked-files=all']).split(b'\0')
    changes, fingerprints, cursor = [], {}, 0
    while cursor < len(records):
        record = records[cursor]; cursor += 1
        if len(record) < 4: continue
        code, path = record[:2].decode(), local(record[3:].decode('utf-8', 'replace'))
        old = None
        if 'R' in code or 'C' in code:
            old = local(records[cursor].decode('utf-8', 'replace')); cursor += 1
        sa, sd = staged.get(path, [0,0]); wa, wd = working.get(path, [0,0])
        if old and old in staged:
            a,d=staged[old]; sa=None if sa is None or a is None else sa+a; sd=None if sd is None or d is None else sd+d
        if old and old in working:
            a,d=working[old]; wa=None if wa is None or a is None else wa+a; wd=None if wd is None or d is None else wd+d
        binary = sa is None or wa is None
        if code == '??':
            try:
                if os.path.islink(os.path.join(root,path)): raise OSError('symbolic link')
                with open(os.path.join(root,path),'rb') as f: data=f.read(MAX_FILE+1)
                source=text(data)
                binary=source is None
                wa=len(source.splitlines()) if source is not None and len(data)<=MAX_FILE else None
                wd=0
            except OSError: wa=wd=None
        fp=fingerprint(path,code,indices.get(path,'')); fingerprints[path]=fp
        changes.append(dict(path=path,oldPath=old,status=code,added=None if sa is None or wa is None else sa+wa,deleted=None if sd is None or wd is None else sd+wd,stagedAdded=sa,stagedDeleted=sd,workingAdded=wa,workingDeleted=wd,binary=binary,fingerprint=fp))
    for path in extras:
        if path not in fingerprints: fingerprints[path]=fingerprint(path,'',indices.get(path,''))
    return dict(root=root,repository=repository,head=head,branch=branch,changes=changes,fingerprints=fingerprints,capturedAt=int(time.time()*1000))
def paths():
    repository=run(['rev-parse','--show-toplevel']).decode().strip()
    names=set()
    for path in run(['ls-files','--cached','--others','--exclude-standard','-z'],cwd=repository).split(b'\0'):
        if not path: continue
        full=os.path.join(repository,path.decode('utf-8','replace'))
        if not os.path.realpath(full).startswith(base+os.sep): names.add(os.path.relpath(full,root))
    return sorted(names)
def collect(names, folder=None):
    entries, total, partial = {}, 0, False
    allowed=os.path.realpath(run(['rev-parse','--show-toplevel']).decode().strip())
    for i,path in enumerate(names):
        if i>=MAX_FILES: partial=True; entries[path]=dict(unknown=True); continue
        full=os.path.normpath(os.path.join(root,path))
        try:
            st=os.lstat(full)
            if os.path.commonpath([allowed,os.path.realpath(full)])!=allowed: partial=True; entries[path]=dict(unknown=True); continue
            if not stat.S_ISREG(st.st_mode) or stat.S_ISLNK(st.st_mode):
                partial=True; entries[path]=dict(hash=fingerprint(path),mode=stat.S_IMODE(st.st_mode),omitted=True); continue
            if st.st_size>MAX_FILE or total+st.st_size>MAX_TOTAL:
                partial=True; entries[path]=dict(hash=fingerprint(path),mode=stat.S_IMODE(st.st_mode),omitted=True); continue
            with open(full,'rb') as f: data=f.read(MAX_FILE+1)
            if len(data)>MAX_FILE: partial=True; entries[path]=dict(hash=fingerprint(path),omitted=True); continue
            total+=len(data)
            entry=dict(hash=digest((digest(data)+':'+str(stat.S_IMODE(st.st_mode))+'::').encode()),mode=stat.S_IMODE(st.st_mode),binary=text(data) is None)
            if folder:
                name=hashlib.sha256(path.encode()).hexdigest()
                with open(os.path.join(folder,name),'wb') as f: f.write(data)
                entry['saved']=name
            else: entry['data']=data
            entries[path]=entry
        except FileNotFoundError: pass
    return entries,partial
try:
    extras=json.loads(extra_json)
    if action=='overview':
        print(json.dumps(overview(extras),ensure_ascii=False));sys.exit(0)
    base=os.path.join(os.environ.get('CODEX_HOME',os.path.expanduser('~/.codex')),'codex-pocket','review-baselines')
    os.makedirs(base,mode=0o700,exist_ok=True)
    if action=='begin':
        for name in os.listdir(base):
            try:
                if str(uuid.UUID(name))==name and time.time()-os.lstat(os.path.join(base,name)).st_mtime>7*86400 and not os.path.islink(os.path.join(base,name)):
                    shutil.rmtree(os.path.join(base,name))
            except (ValueError,OSError): pass
        finished=[]
        for name in os.listdir(base):
            try:
                candidate=os.path.join(base,name)
                if str(uuid.UUID(name))==name and not os.path.islink(candidate) and os.path.isfile(os.path.join(candidate,'report.json')): finished.append((os.path.getmtime(candidate),candidate))
            except (ValueError,OSError): pass
        for _,candidate in sorted(finished,reverse=True)[39:]: shutil.rmtree(candidate)
        token=str(uuid.uuid4());folder=os.path.join(base,token);os.mkdir(folder,0o700)
        initial=overview([]);entries,partial=collect(paths(),folder)
        manifest=dict(root=root,head=initial['head'],branch=initial['branch'],repository=initial['repository'],entries=entries,partial=partial)
        with open(os.path.join(folder,'manifest.json'),'w') as f: json.dump(manifest,f)
        print(json.dumps(dict(token=token,root=root,partial=partial)));sys.exit(0)
    if str(uuid.UUID(token))!=token: raise RuntimeError('无效的本轮记录')
    folder=os.path.join(base,token)
    if os.path.islink(folder): raise RuntimeError('无效的本轮记录')
    with open(os.path.join(folder,'manifest.json')) as f: before=json.load(f)
    if before['root']!=root: raise RuntimeError('本轮记录不属于当前仓库')
    if action=='read':
        with open(os.path.join(folder,'report.json')) as f: report=json.load(f)
        print(json.dumps(report,ensure_ascii=False));sys.exit(0)
    old=before['entries'];names=sorted(set(paths())|set(old));after,partial=collect(names)
    now=overview(names);changes=[];diffs={};size=0
    if before['head']!=now['head']: raise RuntimeError('本轮期间仓库提交发生变化，请查看工作区变更')
    for path in names:
        a,b=old.get(path),after.get(path)
        if (a or {}).get('unknown') or (b or {}).get('unknown'): partial=True; continue
        if (a or {}).get('hash')==(b or {}).get('hash') and (a or {}).get('mode')==(b or {}).get('mode'): continue
        omitted=(a or {}).get('omitted',False) or (b or {}).get('omitted',False)
        left,right=b'',(b or {}).get('data',b'')
        if a and a.get('saved'):
            with open(os.path.join(folder,a['saved']),'rb') as f: left=f.read(MAX_FILE+1)
        first,last=text(left),text(right);binary=first is None or last is None
        added=deleted=None
        if not omitted and not binary:
            patch=''.join(piece if piece.endswith('\n') else piece+'\n\\ No newline at end of file\n' for piece in difflib.unified_diff(first.splitlines(True),last.splitlines(True),fromfile='a/'+path,tofile='b/'+path))
            added=deleted=0
            for tag,i,j,k,l in difflib.SequenceMatcher(None,first.splitlines(True),last.splitlines(True),autojunk=True).get_opcodes():
                if tag in ('replace','insert'): added+=l-k
                if tag in ('replace','delete'): deleted+=j-i
            if not patch: patch='文件权限发生变化'
        else: patch='二进制文件或大文件发生变化，行数暂不可用'
        patch='本轮期间\n'+patch
        size+=len(patch.encode())
        if size<=MAX_DIFF: diffs[path]=patch
        else: partial=True;diffs[path]='本轮期间\n差异过大，请在工作区中逐个文件查看'
        changes.append(dict(path=path,status=' M' if a and b else '??' if b else ' D',added=added,deleted=deleted,binary=binary,fingerprint=now['fingerprints'].get(path,'')))
    report=dict(token=token,root=root,repository=now['repository'],branch=now['branch'],changes=changes,diffs=diffs,capturedAt=now['capturedAt'],partial=partial or before.get('partial',False))
    if action=='finish':
        temporary=os.path.join(folder,'report.tmp')
        with open(temporary,'w') as f: json.dump(report,f,ensure_ascii=False)
        os.replace(temporary,os.path.join(folder,'report.json'))
        for entry in old.values():
            saved=entry.get('saved','')
            if len(saved)==64 and all(c in '0123456789abcdef' for c in saved):
                try: os.remove(os.path.join(folder,saved))
                except FileNotFoundError: pass
    print(json.dumps(report,ensure_ascii=False))
except Exception as error:
    if action=='begin' and 'folder' in globals() and os.path.isdir(folder) and not os.path.islink(folder):
        shutil.rmtree(folder,ignore_errors=True)
    print(json.dumps(dict(error=str(error)),ensure_ascii=False));sys.exit(1)
""".trimIndent()

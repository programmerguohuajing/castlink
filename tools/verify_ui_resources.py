#!/usr/bin/env python3
"""UI 资源静态校验（无需 Android SDK）：
  python3 tools/verify_ui_resources.py [基线提交]
检查：@引用可解析、浅/深色令牌齐全、中英文串齐全、Kotlin 的 R.* 与 findViewById 类型和布局一致、
与基线相比没有丢 id / 改控件类型。不能替代真机验证。
"""
import re, sys, os, subprocess, glob
import xml.etree.ElementTree as ET
ROOT=os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)),'..'))
BASE=sys.argv[1] if len(sys.argv)>1 else 'HEAD'   # 对比基线（提交号）：保证重构后 id/控件类型没有丢失或改变
RES=ROOT+'/app/src/main/res'; JAVA=ROOT+'/app/src/main/java'
ANS='{http://schemas.android.com/apk/res/android}'
errs=[]; ok=[]
def err(m): errs.append(m)

def layout_ids(base):  # id -> set(tag) over all layouts in a res dir
    ids={}
    for f in glob.glob(base+'/layout*/*.xml'):
        for el in ET.parse(f).iter():
            v=el.attrib.get(ANS+'id')
            if v and v.startswith('@+id/'): ids.setdefault(v[5:],set()).add((os.path.basename(f),el.tag))
    return ids
new=layout_ids(RES)
# 基线版本的资源，用于对比
import tempfile; old_dir=tempfile.mkdtemp(); subprocess.run(f'rm -rf {old_dir}; mkdir -p {old_dir}; cd {ROOT} && git archive {BASE} app/src/main/res | tar -x -C {old_dir}',shell=True,check=True)
old=layout_ids(old_dir+'/app/src/main/res')
for i,s in old.items():
    if i not in new: err(f'id removed since v0.1.64: {i}')
    elif {t for _,t in s}!={t for _,t in new[i]}: err(f'id {i} changed element type {s} -> {new[i]}')
print(f'[1] ids: old={len(old)} new={len(new)}; added={sorted(set(new)-set(old))}')

# resource inventory
def names(kind):
    out={'default':set(),'night':set()}
    for d in glob.glob(RES+'/values*'):
        night='night' in d
        for f in glob.glob(d+'/*.xml'):
            for el in ET.parse(f).getroot():
                if el.tag==kind or (kind=='color' and el.tag=='color'): 
                    n=el.attrib.get('name'); 
                    if n: out['night' if night else 'default'].add(n) ; 
                    if not night: out['default'].add(n)
    return out
def inv(kind):
    s=set()
    for d in glob.glob(RES+'/values*'):
        for f in glob.glob(d+'/*.xml'):
            for el in ET.parse(f).getroot():
                if el.tag==kind and el.attrib.get('name'): s.add(el.attrib['name'])
    return s
colors=inv('color')|{os.path.splitext(os.path.basename(f))[0] for f in glob.glob(RES+'/color*/*.xml')}; strings=inv('string'); styles=inv('style'); dimens=inv('dimen')
drawables={os.path.splitext(os.path.basename(f))[0] for f in glob.glob(RES+'/drawable*/*')}
layouts={os.path.splitext(os.path.basename(f))[0] for f in glob.glob(RES+'/layout*/*.xml')}
ids=set(new)
# parity: night colors must define every default color that is a literal; default must define all night ones
dcol=set(re.findall(r'name="(\w+)"',open(RES+'/values/colors.xml').read()))
ncol=set(re.findall(r'name="(\w+)"',open(RES+'/values-night/colors.xml').read()))
for n in ncol-dcol: err(f'night color {n} has no default')
lit={n for n in dcol if re.search(r'name="%s">#'%n,open(RES+'/values/colors.xml').read())}
miss=lit-ncol-{'white','black'}
if miss: err(f'default literal colors without night variant: {sorted(miss)}')
print(f'[2] colors default={len(dcol)} night={len(ncol)} missing-night={sorted(miss)}')
# string parity zh/en
sz=set(re.findall(r'name="(\w+)"',open(RES+'/values/strings.xml').read())); se=set(re.findall(r'name="(\w+)"',open(RES+'/values-en/strings.xml').read()))
if sz-se: err(f'strings missing in en: {sorted(sz-se)}')
print(f'[3] strings zh={len(sz)} en={len(se)} missing-en={sorted(sz-se)} extra-en={sorted(se-sz)}')

# every @ref in res xml resolves
refs=0
for f in glob.glob(RES+'/**/*.xml',recursive=True):
    txt=open(f,encoding='utf-8').read()
    for kind,name in re.findall(r'@(?:android:)?(color|drawable|string|style|layout|dimen|id|\+id)/([\w.]+)',txt):
        pass
    for m in re.finditer(r'@(android:)?(color|drawable|string|style|layout|dimen|id|\+id|mipmap)/([\w.]+)',txt):
        if m.group(1): continue
        kind,name=m.group(2),m.group(3); refs+=1
        pool={'color':colors|drawables,'drawable':drawables|colors,'string':strings,'style':styles,'layout':layouts,'dimen':dimens,'id':ids,'+id':ids,'mipmap':{os.path.splitext(os.path.basename(x))[0] for x in glob.glob(RES+'/mipmap*/*')}}[kind]
        if name not in pool: err(f'{os.path.relpath(f,RES)}: unresolved @{kind}/{name}')
print(f'[4] resolved {refs} @refs in res xml')

# manifest refs
mf=open(ROOT+'/app/src/main/AndroidManifest.xml',encoding='utf-8').read()
for k,n in re.findall(r'@(\w+)/([\w.]+)',mf):
    if k in('style','drawable','mipmap','string','color','xml') :
        pass
# Kotlin
kt=glob.glob(JAVA+'/**/*.kt',recursive=True); cnt=0
tagmap={'Button':{'Button'},'ImageButton':{'ImageButton'},'TextView':{'TextView','Button','EditText'},'View':None}
for f in kt:
    s=open(f,encoding='utf-8').read()
    for kind,name in re.findall(r'(?<![\w.])R\.(string|color|drawable|layout|id|style|dimen)\.(\w+)',s):
        cnt+=1
        pool={'string':strings,'color':colors,'drawable':drawables|colors,'layout':layouts,'id':ids,'style':styles,'dimen':dimens}[kind]
        if name not in pool: err(f'{os.path.basename(f)}: R.{kind}.{name} does not exist')
    for typ,name in re.findall(r'findViewById<(\w+)>\(\s*R\.id\.(\w+)\s*\)',s):
        cnt+=1
        if name in new:
            tags={t.split('.')[-1] for _,t in new[name]}
            if typ=='View': continue
            okset={typ}|({'Button','EditText','CheckBox','RadioButton','Switch','CompoundButton'} if typ=='TextView' else set())|({'RadioButton','CheckBox','Switch'} if typ=='CompoundButton' else set())|({'ImageButton'} if typ=='ImageView' else set())|({'Button'} if typ=='TextView' else set())
            if typ=='ViewGroup': okset|={'LinearLayout','FrameLayout','ScrollView','RelativeLayout','RadioGroup','ViewFlipper','HorizontalScrollView'}
            if typ in('LinearLayout','FrameLayout','ScrollView','RadioGroup','ViewFlipper','SurfaceView','ImageView','ListView','Spinner','EditText','Button','ImageButton','TextView','RadioButton','CheckBox','Switch'):
                if not (tags & okset): err(f'{os.path.basename(f)}: findViewById<{typ}>(R.id.{name}) but layout element is {tags}')
print(f'[5] checked {cnt} Kotlin R.* usages / findViewById casts')


# 各布局变体（layout-land / layout-sw*dp-land …）必须包含默认布局的全部 id：Kotlin 用非空类型 findViewById，缺一个就是启动崩溃
variants=0
for f in glob.glob(RES+'/layout-*/*.xml'):
    base=os.path.join(RES,'layout',os.path.basename(f))
    if not os.path.exists(base): continue
    def idset(path): return {e.attrib[ANS+'id'][5:] for e in ET.parse(path).iter() if e.attrib.get(ANS+'id','').startswith('@+id/')}
    miss=idset(base)-idset(f); variants+=1
    if miss: err(f'{os.path.relpath(f,RES)} is missing ids that layout/ has: {sorted(miss)}')
print(f'[6] layout variants checked for id superset: {variants}')

# dpad focus list members & click wiring for new row
sa=open(JAVA+'/com/castlink/receiver/ui/SettingsActivity.kt',encoding='utf-8').read()
for n in re.findall(r'findViewById\(R\.id\.(\w+)\)',sa):
    if n not in ids: err(f'dpad list references missing id {n}')
for n in ('row_theme','tv_theme_summary','row_av_trim','btn_av_trim'):
    if n not in ids: err(f'missing id {n}')
# every id the Activities look up must live in the layout that the activity inflates
print('\nERRORS:' if errs else '\nNO ERRORS'); [print(' -',e) for e in errs]
sys.exit(1 if errs else 0)

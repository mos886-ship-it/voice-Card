# 大D logo 產生器：王羲之草書風格 dw（直書）＋左下白文長方印「大D」
# 用法：python design/gen/logo_final.py  → 產生預覽 design/logo_final.html 與 App 向量圖示
import math, os
def bez(p0,p1,p2,p3,n=40):
    return [((1-t)**3*p0[0]+3*(1-t)**2*t*p1[0]+3*(1-t)*t*t*p2[0]+t**3*p3[0],
             (1-t)**3*p0[1]+3*(1-t)**2*t*p1[1]+3*(1-t)*t*t*p2[1]+t**3*p3[1]) for t in [i/n for i in range(n+1)]]
def brush(segs, tf=lambda p:p, k=1.0):
    """毛筆筆畫：每段 (p0,p1,p2,p3,起粗,止粗)，[tf] 是座標轉換、[k] 是粗細倍率。"""
    pts=[];ws=[]
    for (p0,p1,p2,p3,w0,w1) in segs:
        q=bez(p0,p1,p2,p3)
        for i,p in enumerate(q):
            if pts and i==0: continue
            t=i/(len(q)-1); s=t*t*(3-2*t); pts.append(tf(p)); ws.append((w0+(w1-w0)*s)*k)
    n=len(pts); L=[];R=[]; nib=math.radians(35)
    for i,(x,y) in enumerate(pts):
        a=pts[max(i-1,0)]; b=pts[min(i+1,n-1)]
        dx,dy=b[0]-a[0],b[1]-a[1]; d=math.hypot(dx,dy) or 1
        nx,ny=-dy/d,dx/d; w=ws[i]*(0.78+0.22*abs(math.sin(math.atan2(dy,dx)-nib)))
        L.append((x+nx*w/2,y+ny*w/2)); R.append((x-nx*w/2,y-ny*w/2))
    return "M"+" L".join(f"{x:.2f},{y:.2f}" for x,y in L+R[::-1])+" Z"
def circle(cx,cy,r): return f"M{cx+r:.2f},{cy:.2f} a{r:.2f},{r:.2f} 0 1,1 -{2*r:.2f},0 a{r:.2f},{r:.2f} 0 1,1 {2*r:.2f},0 Z"
# ---- dw（更奔放版），直書：d 在上、w 在下 ----
E2=[((48,47),(40,42),(29,51),(31.5,61),5.6,6.8),((31.5,61),(33,69),(43,68),(47.5,58),6.4,2.4),
    ((47.5,58),(50,48),(52,39),(56,29),2.0,0.8),((56,29),(51.5,41),(47,55),(47.5,66.5),1.4,7.6),
    ((47.5,66.5),(48.5,71),(54,61),(56.5,50),3.2,0.7),((56.5,50),(56,59),(57.5,66),(60,66.5),1.3,6.0),
    ((60,66.5),(63,66),(63.5,57),(65,52),4.2,1.1),((65,52),(65,60),(66.5,66.5),(69.5,66),1.5,5.4),
    ((69.5,66),(75,63),(78,52),(79.5,44),4.4,1.2),((79.5,44),(80.5,40),(77,40),(76.5,43.5),1.2,0.3)]
SC=0.74
tfD=lambda p:(58+(p[0]-43)*SC, 38.5+(p[1]-50)*SC)
tfW=lambda p:(60+(p[0]-68)*SC, 68+(p[1]-56)*SC)
D_PATH=brush(E2[:5],tfD,SC); W_PATH=brush(E2[5:],tfW,SC)
dot=tfD((47.6,47.1)); DOT_PATH=circle(dot[0],dot[1],3.1*SC)
PAPER="#F2EADB"; INK="#111111"; RED="#B5121B"
# ---- 白文長方印：直立長方形紅底，白字「大」在上、「D」在下（落款印） ----
import random
SX,SY,SW_,SH_=26.5,57.5,9.4,18.0      # 印面左上角、寬、高
SEAL_SW=1.0
SEAL_RECT=f"M{SX+0.8},{SY} h{SW_-1.6} a0.8,0.8 0 0 1 0.8,0.8 v{SH_-1.6} a0.8,0.8 0 0 1 -0.8,0.8 h-{SW_-1.6} a0.8,0.8 0 0 1 -0.8,-0.8 v-{SH_-1.6} a0.8,0.8 0 0 1 0.8,-0.8 Z"
def place(cx,cy,size):
    return lambda x,y:(cx+(x-5)*size/10, cy+(y-5)*size/10)
def strokes(cmds, f):
    out=[]
    for c in cmds:
        pts=[f(c[i],c[i+1]) for i in range(1,len(c),2)]
        out.append(c[0]+" ".join(f"{x:.2f},{y:.2f}" for x,y in pts))
    return " ".join(out)
# 「大」：頂端那筆從正中央垂直上來（偏右就會像「犬」）
DA_CMDS=[('M',1.4,3.9),('Q',5.0,3.0,8.7,3.5),
         ('M',5.0,0.8),('L',5.0,3.9),('Q',4.8,6.9,1.7,9.3),
         ('M',5.2,5.3),('Q',6.9,7.7,9.3,9.0)]
DD_CMDS=[('M',2.6,1.0),('L',2.6,9.0),('M',2.6,1.0),('C',10.2,0.6,10.2,9.4,2.6,9.0)]
SEAL_TEXT=strokes(DA_CMDS,place(SX+SW_/2,SY+SH_*0.28,6.6))+" "+strokes(DD_CMDS,place(SX+SW_/2+0.3,SY+SH_*0.72,6.0))
# 蓋印的斑駁：邊緣缺口＋印面幾個小白點
_r=random.Random(11); WEAR=[]
for i in range(16):
    side=_r.randint(0,3)
    x={0:SX+_r.uniform(0.5,SW_-0.5),1:SX+SW_-_r.uniform(0,0.35),2:SX+_r.uniform(0.5,SW_-0.5),3:SX+_r.uniform(0,0.35)}[side]
    y={0:SY+_r.uniform(0,0.35),1:SY+_r.uniform(0.5,SH_-0.5),2:SY+SH_-_r.uniform(0,0.35),3:SY+_r.uniform(0.5,SH_-0.5)}[side]
    WEAR.append((x,y,_r.uniform(0.18,0.42)))
for i in range(6):
    WEAR.append((SX+_r.uniform(1,SW_-1),SY+_r.uniform(1,SH_-1),_r.uniform(0.12,0.26)))
WEAR_PATH=" ".join(circle(x,y,r) for x,y,r in WEAR)
def svg_body(seal="white"):
    s=f'<circle cx="54" cy="54" r="36" fill="{PAPER}"/>'
    s+=f'<g fill="{INK}"><path d="{D_PATH}"/><path d="{DOT_PATH}"/><path d="{W_PATH}"/></g>'
    s+=f'<path d="{SEAL_RECT}" fill="{RED}"/>'
    s+=f'<path d="{SEAL_TEXT}" fill="none" stroke="{PAPER}" stroke-width="{SEAL_SW}" stroke-linecap="round" stroke-linejoin="round"/>'
    s+=f'<path d="{WEAR_PATH}" fill="{PAPER}"/>'
    return s
if __name__=="__main__":
    root=os.path.join(os.path.dirname(__file__),"..","..")
    def svg(size): return f'<svg width="{size}" height="{size}" viewBox="18 18 72 72" xmlns="http://www.w3.org/2000/svg">{svg_body()}</svg>'
    open(os.path.join(root,'design','logo_final.html'),'w',encoding='utf-8').write(
        '<html><head><meta charset="utf-8"><title>大D logo 定稿：直書＋白文長方印</title></head><body style="margin:0;background:#2a2e37;padding:32px;display:flex;gap:28px;align-items:center">'
        +svg(320)+svg(160)+svg(96)+svg(56)+svg(36)+'</body></html>')
    print("preview ok")

# ---------------- Android 向量圖示 ----------------
def android_files(root):
    head='<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    android:width="{w}dp" android:height="{w}dp"\n    android:viewportWidth="{w}" android:viewportHeight="{w}">\n'
    def ink(fill): return ''.join(f'        <path android:fillColor="{fill}" android:pathData="{p}" />\n' for p in (D_PATH,DOT_PATH,W_PATH))
    def seal(red,paper,wear=True,mono=False):
        if mono:  # 單色主題圖示：只畫印框和字的線條
            return (f'        <path android:strokeColor="{red}" android:strokeWidth="0.9" android:pathData="{SEAL_RECT}" />\n'
                    f'        <path android:strokeColor="{red}" android:strokeWidth="{SEAL_SW}" android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="{SEAL_TEXT}" />\n')
        out=f'        <path android:fillColor="{red}" android:pathData="{SEAL_RECT}" />\n'
        out+=f'        <path android:strokeColor="{paper}" android:strokeWidth="{SEAL_SW}" android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="{SEAL_TEXT}" />\n'
        if wear: out+=f'        <path android:fillColor="{paper}" android:pathData="{WEAR_PATH}" />\n'
        return out
    grp='    <group android:pivotX="54" android:pivotY="54" android:scaleX="0.86" android:scaleY="0.86">\n'
    bg=head.format(w=108)+'    <!-- 宣紙底 -->\n    <path android:fillColor="#FFF2EADB" android:pathData="M0,0h108v108h-108z" />\n</vector>\n'
    fg=(head.format(w=108)+'    <!-- 大D 標誌：王羲之草書風格 dw（直書）＋左下白文長方印「大D」。由 design/gen/logo_final.py 產生 -->\n'
        +grp+ink("#FF111111")+seal("#FFB5121B","#FFF2EADB")+'    </group>\n</vector>\n')
    mono=head.format(w=108)+grp+ink("#FFFFFFFF")+seal("#FFFFFFFF","#00000000",mono=True)+'    </group>\n</vector>\n'
    logo=(head.format(w=72)+'    <group android:translateX="-18" android:translateY="-18">\n'
          +'        <path android:fillColor="#FFF2EADB" android:pathData="M90,54 a36,36 0 1,1 -72,0 a36,36 0 1,1 72,0 Z" />\n'
          +ink("#FF111111")+seal("#FFB5121B","#FFF2EADB")+'    </group>\n</vector>\n')
    for mod in ('app','wear'):
        b=os.path.join(root,mod,'src','main','res','drawable')
        open(os.path.join(b,'ic_launcher_background.xml'),'w',encoding='utf-8').write(bg)
        open(os.path.join(b,'ic_launcher_foreground.xml'),'w',encoding='utf-8').write(fg)
        open(os.path.join(b,'ic_launcher_monochrome.xml'),'w',encoding='utf-8').write(mono)
    for mod in ('app','wear'):
        open(os.path.join(root,mod,'src','main','res','drawable','ic_logo.xml'),'w',encoding='utf-8').write(logo)
if __name__=="__main__":
    android_files(os.path.join(os.path.dirname(__file__),"..",".."))
    print("android ok")

import numpy as np, cv2, csv, json
from ai_edge_litert.interpreter import Interpreter
it = Interpreter(model_path="work/MobileFaceNet.tflite"); it.allocate_tensors()
inp=it.get_input_details()[0]["index"]; out=it.get_output_details()[0]["index"]
casc=cv2.CascadeClassifier(cv2.data.haarcascades+"haarcascade_frontalface_default.xml")
def crop(im):
    g=cv2.cvtColor(im,cv2.COLOR_BGR2GRAY)
    f=casc.detectMultiScale(g,1.1,4,minSize=(40,40))
    if len(f)==0: return None
    x,y,w,h=max(f,key=lambda r:r[2]*r[3])
    side=int(max(w,h)*1.15); cx,cy=x+w//2,y+h//2
    side=min(side,im.shape[0],im.shape[1]); l=min(max(cx-side//2,0),im.shape[1]-side); t=min(max(cy-side//2,0),im.shape[0]-side)
    return im[t:t+side,l:l+side]
def emb(c):
    c=cv2.resize(c,(112,112),interpolation=cv2.INTER_AREA); rgb=cv2.cvtColor(c,cv2.COLOR_BGR2RGB).astype(np.float32)
    x=(rgb-127.5)/128; it.set_tensor(inp,np.stack([x,x])); it.invoke(); e=it.get_tensor(out)[0].copy(); return e/np.linalg.norm(e)
def degrade(c):
    # phone front camera in a dim room: small face, blur, darker, noise, JPEG
    s=cv2.resize(c,(56,56),interpolation=cv2.INTER_AREA); s=cv2.GaussianBlur(s,(0,0),1.0)
    s=np.clip(s.astype(np.float32)*0.55+np.random.RandomState(1).normal(0,6,s.shape),0,255).astype(np.uint8)
    ok,b=cv2.imencode(".jpg",s,[cv2.IMWRITE_JPEG_QUALITY,45]); return cv2.imdecode(b,1)
pairs=list(csv.reader(open("work/deepface/master.csv")))[1:]
names=sorted({p[0] for p in pairs}|{p[1] for p in pairs})
E={};D={};miss=[]
for n in names:
    c=crop(cv2.imread("work/deepface/img/"+n))
    if c is None: miss.append(n); continue
    E[n]=emb(c); D[n]=emb(degrade(c))
print("images",len(names),"no face found:",miss)
def scores(T):
    g=[float(T[a]@T[b]) for a,b,d in pairs if a in T and b in T and d=="Yes"]
    i=[float(T[a]@T[b]) for a,b,d in pairs if a in T and b in T and d!="Yes"]
    return np.array(g),np.array(i)
res={}
for tag,T in (("clean",E),("degraded",D)):
    g,i=scores(T); res[tag]=(g,i)
    print(f"\n== {tag}: genuine pairs={len(g)} impostor pairs={len(i)}")
    print("  genuine  min %.2f p5 %.2f p25 %.2f median %.2f max %.2f"%(g.min(),np.percentile(g,5),np.percentile(g,25),np.median(g),g.max()))
    print("  impostor min %.2f median %.2f p95 %.2f p99 %.2f max %.2f"%(i.min(),np.median(i),np.percentile(i,95),np.percentile(i,99),i.max()))
    for th in (0.25,0.30,0.35,0.40,0.45,0.50,0.55,0.60):
        print(f"  th={th:.2f}  owner-rejected(FRR)={np.mean(g<th)*100:5.1f}%  stranger-accepted(FAR)={np.mean(i>=th)*100:5.1f}%")
# best separating threshold (min FAR+FRR) on each
for tag,(g,i) in res.items():
    ths=np.linspace(-0.2,0.9,221); cost=[np.mean(g<t)+np.mean(i>=t) for t in ths]; k=int(np.argmin(cost))
    print(f"\n{tag}: lowest FAR+FRR at th={ths[k]:.2f} (FRR {np.mean(g<ths[k])*100:.1f}%, FAR {np.mean(i>=ths[k])*100:.1f}%)")
json.dump({t:{"g":g.tolist(),"i":i.tolist()} for t,(g,i) in res.items()},open("work/calib_scores.json","w"))

import numpy as np, cv2, csv, itertools, random
exec(open("tools/threshold_experiment/measure_thresholds.py").read().split("pairs=list")[0])   # reuse crop/emb/degrade/model
pairs=list(csv.reader(open("work/deepface/master.csv")))[1:]
names=sorted({p[0] for p in pairs}|{p[1] for p in pairs})
par={n:n for n in names}
def f(x):
    while par[x]!=x: par[x]=par[par[x]]; x=par[x]
    return x
for a,b,d in pairs:
    if d=="Yes": par[f(a)]=f(b)
groups={}
for n in names: groups.setdefault(f(n),[]).append(n)
ids=[g for g in groups.values() if len(g)>=3]
print("identities with >=3 photos:",[len(g) for g in ids],"| singles/pairs:",sum(1 for g in groups.values() if len(g)<3))
E={n:emb(crop(cv2.imread("work/deepface/img/"+n))) for n in names}
D={n:emb(degrade(crop(cv2.imread("work/deepface/img/"+n)))) for n in names}
def mean_unit(vs): m=np.sum(vs,axis=0); return m/np.linalg.norm(m)
def minsim(T,members):
    m=mean_unit([T[x] for x in members]); return min(float(T[x]@m) for x in members)
for tag,T in (("clean",E),("degraded",D)):
    same=[minsim(T,g) for g in ids]
    # mixed: 3 photos of person A + 2 of person B (or 4 of A + 1 of B) -> a bad enrollment
    random.seed(0); mixed=[]
    for _ in range(300):
        A,B=random.sample(groups_list:=[g for g in groups.values() if len(g)>=2],2)
        if len(A)<2: continue
        mem=random.sample(A,min(len(A),4))+random.sample(B,1); mixed.append(minsim(T,mem))
    same2=[]
    for g in ids:
        for k in range(min(len(g),5),2,-1):
            for sub in itertools.combinations(g,k): same2.append(minsim(T,sub))
    print(f"\n{tag}: genuine enrollment sets n={len(same2)}: min-sample-vs-profile  min {min(same2):.2f} p5 {np.percentile(same2,5):.2f} median {np.median(same2):.2f}")
    print(f"{tag}: mixed-person sets n={len(mixed)}: max {max(mixed):.2f} p95 {np.percentile(mixed,95):.2f} median {np.median(mixed):.2f}")

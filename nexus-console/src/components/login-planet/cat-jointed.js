import {tailCenters} from './tail-rest.js';
// Isolated runtime animation: eyelid surfaces, anchored tail deformation, no GLB mutation.
export function createCatAnimation(T, root) {
 const eyes=[];root.traverse(o=>{if(o.isMesh&&o.name.startsWith('Amber_eye_rim'))eyes.push(o);});
 if(eyes.length!==2)throw Error('Expected two eye rims, found '+eyes.length);
 const lids=[],seams=[];
 for(const eye of eyes){
  eye.geometry.computeBoundingBox();const b=eye.geometry.boundingBox,c=b.getCenter(new T.Vector3()),r=b.getSize(new T.Vector3()).multiplyScalar(.5);
  const material=new T.MeshStandardMaterial({color:new T.Color(.87,.285,.045),roughness:.72,side:T.DoubleSide});
  const points=[];
  for(let i=0;i<=24;i++){const u=-.96+1.92*i/24;points.push(new T.Vector3(c.x+r.x*1.05*u,c.y-r.y*.105-.008*(1-u*u),c.z+.014+r.z*1.18*Math.sqrt(1-u*u-.01)));}
  const seam=new T.Mesh(new T.TubeGeometry(new T.CatmullRomCurve3(points),24,.005,6,false),new T.MeshStandardMaterial({color:0x6b3c20,roughness:.8,transparent:true}));
  seam.name='Closed eyelid crease';seam.position.copy(eye.position);seam.quaternion.copy(eye.quaternion);seam.scale.copy(eye.scale);eye.parent.add(seam);seams.push(seam);
  // A forward shell around the eye, separately opening from above and below.
  for(const upper of [true,false]){
   const nx=24,ny=12,g=new T.BufferGeometry(),pos=new Float32Array((nx+1)*(ny+1)*3),idx=[];
   for(let y=0;y<ny;y++)for(let x=0;x<nx;x++){const a=y*(nx+1)+x;idx.push(a,a+1,a+nx+1,a+1,a+nx+2,a+nx+1);}
   g.setAttribute('position',new T.BufferAttribute(pos,3));g.setIndex(idx);
   const m=new T.Mesh(g,material);m.name=upper?'Upper eyelid':'Lower eyelid';m.position.copy(eye.position);m.quaternion.copy(eye.quaternion);m.scale.copy(eye.scale);m.castShadow=true;eye.parent.add(m);
   lids.push({m,g,pos,c,r,upper,nx,ny});
  }
 }
 const tail=(()=>{let t;root.traverse(o=>{if(o.isMesh&&o.name.startsWith('Hooked_tail'))t=o;});return t;})();
 if(!tail)throw Error('Missing tail');
 // Local geometry is a copy. The loaded source asset remains untouched.
 tail.geometry=tail.geometry.clone();const tp=tail.geometry.attributes.position,base=tp.array.slice();
 let phase=0,amplitude=0,blinkStart=-1,nextBlink=performance.now()+randomDelay(),close=0;
 const debug={blinks:[],closure:0,tailAmplitude:0,tailMaxDisplacement:0,tracking:false,nextBlink,eyeCount:eyes.length,lidCount:lids.length,forceClosure:null};
 const smooth=x=>x*x*(3-2*x);
 function randomDelay(){return 7000+Math.random()*3000;}
 function paintLids(value){
  for(const seam of seams){seam.visible=value>.85;seam.material.opacity=T.MathUtils.clamp((value-.85)/.15,0,1);}
  for(const l of lids){l.m.visible=value>.001;if(!l.m.visible)continue;
   const {pos,c,r,nx,ny,upper,g}=l;const boundary=Math.asin(upper?1-1.10*value:-1+.90*value);
   for(let y=0;y<=ny;y++){
    const lat=upper?boundary+(Math.PI/2-boundary)*y/ny:-Math.PI/2+(boundary+Math.PI/2)*y/ny;
    for(let x=0;x<=nx;x++){const lon=-Math.PI/2+Math.PI*x/nx,k=(y*(nx+1)+x)*3;
     pos[k]=c.x+r.x*1.05*Math.cos(lat)*Math.sin(lon);
     pos[k+1]=c.y+r.y*1.05*Math.sin(lat);
     pos[k+2]=c.z+.012+r.z*1.18*Math.cos(lat)*Math.cos(lon);
    }
   }g.attributes.position.needsUpdate=true;g.computeVertexNormals();g.computeBoundingSphere();
  }
 }
 const rest=tailCenters.map(p=>new T.Vector3(...p)), joints=rest.map(p=>p.clone());
 const lengths=rest.slice(1).map((p,i)=>p.distanceTo(rest[i]));
 const totalLength=lengths.reduce((a,b)=>a+b,0),arc=[0];for(const length of lengths)arc.push(arc.at(-1)+length);
 const rotations=lengths.map(()=>new T.Quaternion()),mapping=[];
 const point=new T.Vector3(),delta=new T.Vector3(),closest=new T.Vector3();
 for(let i=0;i<tp.count;i++){
  point.fromArray(base,i*3);let best=Infinity,entry;
  for(let k=0;k<lengths.length;k++){
   delta.subVectors(rest[k+1],rest[k]);const t=T.MathUtils.clamp(point.clone().sub(rest[k]).dot(delta)/delta.lengthSq(),0,1);
   closest.copy(rest[k]).addScaledVector(delta,t);const d=point.distanceToSquared(closest);
   if(d<best){best=d;entry={k,t,offset:point.clone().sub(closest)};}
  }mapping.push(entry);
 }
 debug.forceTailPhase=null;
 function bendTail(a){
  const ph=debug.forceTailPhase===null?phase:debug.forceTailPhase;
  joints[0].copy(rest[0]);
  // Each bone keeps its rest length. Rotation smoothly grows beyond the anchored base.
  for(let k=0;k<lengths.length;k++){
   const u=arc[k]/totalLength,w=smooth(T.MathUtils.clamp((u-.08)/.70,0,1));
   const yaw=.26*Math.sin(ph-.55*u)*a*w;
   const pitch=.045*Math.sin(ph*2-.45*u)*a*w;
   rotations[k].setFromEuler(new T.Euler(pitch,yaw,0,'YXZ'));
   delta.subVectors(rest[k+1],rest[k]).applyQuaternion(rotations[k]);
   joints[k+1].copy(joints[k]).add(delta);
  }
  let max=0,radiusError=0;
  for(let i=0;i<tp.count;i++){
   const {k,t,offset}=mapping[i];closest.copy(joints[k]).lerp(joints[k+1],t);
   point.copy(offset).applyQuaternion(rotations[k]).add(closest);tp.setXYZ(i,point.x,point.y,point.z);
   max=Math.max(max,point.distanceTo(new T.Vector3().fromArray(base,i*3)));
   radiusError=Math.max(radiusError,Math.abs(point.distanceTo(closest)-offset.length()));
  }
  tp.needsUpdate=true;tail.geometry.computeVertexNormals();tail.geometry.computeBoundingSphere();
  debug.tailMaxDisplacement=max;debug.centerlineLength=totalLength;
  debug.deformedLength=joints.slice(1).reduce((n,p,k)=>n+p.distanceTo(joints[k]),0);
  debug.radiusError=radiusError;debug.anchorError=joints[0].distanceTo(rest[0]);
 }
 debug.auditTail=()=>{
  const index=tail.geometry.index.array;let maxEdgeError=0,totalOld=0,totalNew=0;
  for(let i=0;i<index.length;i+=3)for(let e=0;e<3;e++){
   const ia=index[i+e],ib=index[i+(e+1)%3];const old=new T.Vector3().fromArray(base,ia*3).distanceTo(new T.Vector3().fromArray(base,ib*3));
   const current=new T.Vector3().fromBufferAttribute(tp,ia).distanceTo(new T.Vector3().fromBufferAttribute(tp,ib));
   if(old>.00001)maxEdgeError=Math.max(maxEdgeError,Math.abs(current/old-1));totalOld+=old;totalNew+=current;
  }
  const targets=[];root.traverse(o=>{if(o.isMesh&&(/Soft_pear|Folded_thigh|Pear.round_face/).test(o.name))targets.push(o);});
  const ray=new T.Raycaster(),direction=new T.Vector3(.893,.341,.293).normalize();let sampled=0,restInside=0,currentInside=0;
  function inside(p){for(const m of targets){const box=new T.Box3().setFromObject(m);if(!box.containsPoint(p))continue;const side=m.material.side;m.material.side=T.DoubleSide;ray.set(p,direction);const hits=ray.intersectObject(m,false);m.material.side=side;const unique=[];for(const hit of hits)if(!unique.length||Math.abs(unique.at(-1)-hit.distance)>1e-5)unique.push(hit.distance);if(unique.length%2)return true;}return false;}
  for(let i=0;i<tp.count;i+=Math.max(1,Math.floor(tp.count/180))){const m=mapping[i];if(arc[m.k]/totalLength<.22)continue;sampled++;if(inside(tail.localToWorld(new T.Vector3().fromArray(base,i*3))))restInside++;if(inside(tail.localToWorld(new T.Vector3().fromBufferAttribute(tp,i))))currentInside++;}
  tail.geometry.computeBoundingBox();const box=tail.geometry.boundingBox;
  return {maxEdgeRelativeChange:maxEdgeError,meanEdgeLengthRatio:totalNew/totalOld,anchor:rest[0].toArray(),segmentCount:lengths.length,bounds:{min:box.min.toArray(),max:box.max.toArray()},collisionSample:{samples:sampled,bodyMeshes:targets.length,restInside,currentInside,excludesFirst22Percent:true}};
 };
 paintLids(0);
 const api={active:false,debug,resetClock(now){blinkStart=-1;close=0;nextBlink=now+randomDelay();debug.nextBlink=nextBlink;amplitude=0;paintLids(0);bendTail(0);debug.closure=0;debug.tailAmplitude=0;api.active=false;},
 update(now,dt,{tracking,reduced,hidden}){
  debug.tracking=tracking;
  if(reduced||hidden){api.resetClock(now);return;}
  if(blinkStart<0&&now>=nextBlink){blinkStart=now;debug.blinks.push(now);if(debug.blinks.length>30)debug.blinks.shift();}
  if(blinkStart>=0){const t=now-blinkStart;close=t<110?smooth(t/110):t<145?1:t<330?1-smooth((t-145)/185):0;if(t>=330){blinkStart=-1;nextBlink=debug.blinks.at(-1)+randomDelay();debug.nextBlink=nextBlink;}}
  if(debug.forceClosure!==null)close=debug.forceClosure;
  paintLids(close);debug.closure=close;
  const target=tracking?1:0;amplitude+=(target-amplitude)*(1-Math.exp(-7*dt));if(amplitude<.0005)amplitude=0;
  if(tracking||amplitude)phase+=dt*4.4;
  bendTail(amplitude);debug.tailAmplitude=amplitude;debug.tailPhase=phase;
  api.active=blinkStart>=0||amplitude>0;
 }};
 return api;
}

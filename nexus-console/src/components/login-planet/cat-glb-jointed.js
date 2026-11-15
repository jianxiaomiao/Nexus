import { createCatAnimation } from './cat-jointed.js';
import modelData from './model-data.js';
import {GLTFLoader} from './vendor/GLTFLoader.js';
export async function loadCat(T,createOriginal){
 const bytes=Uint8Array.from(atob(modelData), c=>c.charCodeAt(0));
 const gltf=await new GLTFLoader().parseAsync(bytes.buffer, '');
 const root=gltf.scene;
 const warm = true;
 let recolored=0;
 if(warm) root.traverse(o=>{
  if(!o.isMesh || !o.geometry.attributes.color)return;
  const color=o.geometry.attributes.color;
  for(let i=0;i<color.count;i++){
   const r=color.getX(i),g=color.getY(i),b=color.getZ(i);
   // Linear-space adjustment to orange pigment only. Cream/white are excluded.
   const w=T.MathUtils.smoothstep((r-g)/Math.max(r,.001),.20,.48);
   if(w>0){color.setXYZ(i,r*(1-.08*w),g*(1-.22*w),b*(1-.35*w));recolored++;}
  }
  color.needsUpdate=true;
 });
 const target=createOriginal(T).group;
 const desired=new T.Box3().setFromObject(target);const actual=new T.Box3().setFromObject(root);
 const height=desired.max.y-desired.min.y;const scale=height/(actual.max.y-actual.min.y);
 root.scale.setScalar(scale);root.updateMatrixWorld(true);
 const box=new T.Box3().setFromObject(root);const a=box.getCenter(new T.Vector3()),d=desired.getCenter(new T.Vector3());
 root.position.set(d.x-a.x,desired.min.y-box.min.y,d.z-a.z);
 const group=new T.Group();group.add(root);
 const headNode=root.getObjectByName('Head_pose__frame_1_straight_-_frame_24_curious') || (()=>{let n;root.traverse(o=>{if(o.name.startsWith('Head_pose')||o.name.startsWith('Head pose'))n=o;});return n;})();
 if(!headNode)throw Error('Missing head transform');
 const head=new T.Group();head.name='TrackingOffset';head.position.copy(headNode.position);headNode.parent.add(head);head.add(headNode);headNode.position.set(0,0,0);
 let tris=0,parts=0,colors=0;root.traverse(o=>{if(o.isMesh){parts++;tris+=(o.geometry.index?o.geometry.index.count:o.geometry.attributes.position.count)/3;if(o.geometry.attributes.color)colors++;o.castShadow=o.receiveShadow=true;}});
 // Dispose temporary original geometry used only for scale reference.
 target.traverse(o=>{if(o.isMesh){o.geometry.dispose();for(const m of Array.isArray(o.material)?o.material:[o.material])m.dispose();}});
 const animation=createCatAnimation(T,root);
 return {group,head,animation};
}

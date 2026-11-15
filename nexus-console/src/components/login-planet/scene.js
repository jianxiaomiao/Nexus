import { loadCat } from './cat-glb-jointed.js';
import * as T from './vendor/three.module.min.js';
import { mergeGeometries } from './vendor/BufferGeometryUtils.js';
import { createCat as createOriginalCat } from './cat.js';
import { createCottage } from './cottage.js';
import { createIsland } from './island.js';

const clamp = T.MathUtils.clamp;
const TAU = Math.PI * 2;
const initialYaw = 0.15;
const initialPitch = 0.205;
const wrap = angle => Math.atan2(Math.sin(angle), Math.cos(angle));

// Bake static objects into a few draw calls. The neck keeps its own coordinate system.
function batch(root, skip) {
  root.updateWorldMatrix(true, true);
  const local = root.matrixWorld.clone().invert();
  const buckets = new Map();
  const meshes = [];
  const collect = node => {
    if (node === skip) return;
    if (node.isMesh && !Array.isArray(node.material)) meshes.push(node);
    node.children.forEach(collect);
  };
  collect(root);
  for (const mesh of meshes) {
    const mat = mesh.material;
    const key = [mat.type,mat.map?.uuid,mat.bumpMap?.uuid,mat.roughness,mat.metalness,
      mat.emissive?.getHex(),mat.emissiveIntensity,mat.flatShading,mat.side,mat.transparent,mat.opacity,
      mat.depthWrite,mat.polygonOffset,mat.polygonOffsetFactor,mesh.castShadow,mesh.receiveShadow].join('/');
    if (!buckets.has(key)) {
      const material = mat.clone();
      material.color.set(0xffffff);
      material.vertexColors = true;
      buckets.set(key,{ material, geometries: [], cast:mesh.castShadow, receive:mesh.receiveShadow });
    }
    const geo = mesh.geometry.clone();
    geo.applyMatrix4(new T.Matrix4().multiplyMatrices(local, mesh.matrixWorld));
    if (!geo.attributes.normal) geo.computeVertexNormals();
    const count = geo.attributes.position.count;
    const colors = new Float32Array(count * 3);
    const original = geo.attributes.color;
    for (let i = 0; i < count; i++) {
      colors[i*3] = mat.color.r * (original?.getX(i) ?? 1);
      colors[i*3+1] = mat.color.g * (original?.getY(i) ?? 1);
      colors[i*3+2] = mat.color.b * (original?.getZ(i) ?? 1);
    }
    geo.setAttribute('color', new T.BufferAttribute(colors,3));
    if (!geo.attributes.uv) geo.setAttribute('uv',new T.BufferAttribute(new Float32Array(count*2),2));
    if (!geo.index) geo.setIndex(Array.from({length:count}, (_,i)=>i));
    geo.clearGroups();
    buckets.get(key).geometries.push(geo);
    mesh.removeFromParent();
  }
  new Set(meshes.map(m=>m.geometry)).forEach(g=>g.dispose());
  new Set(meshes.map(m=>m.material)).forEach(m=>m.dispose());
  for (const {material, geometries, cast, receive} of buckets.values()) {
    const merged = mergeGeometries(geometries);
    const mesh = new T.Mesh(merged, material);
    mesh.castShadow = cast;
    mesh.receiveShadow = receive;
    root.add(mesh);
    geometries.forEach(g => g.dispose());
  }
}

function addSky(group) {
  const moon = new T.Shape();
  moon.moveTo(.12,.39);
  moon.bezierCurveTo(-.34,.48,-.57,-.11,-.25,-.36);
  moon.bezierCurveTo(-.06,-.53,.27,-.43,.34,-.19);
  moon.bezierCurveTo(.07,-.34,-.16,-.16,-.16,.06);
  moon.bezierCurveTo(-.16,.23,-.02,.34,.12,.39);
  const gold = new T.MeshStandardMaterial({color:0xf6b73d,roughness:.38,metalness:.16});
  const extrude = (shape,depth,round) => new T.ExtrudeGeometry(shape,{
    depth,bevelEnabled:true,bevelSize:round,bevelThickness:round,bevelSegments:4,curveSegments:18
  });
  const crescent = new T.Mesh(extrude(moon,.10,.035),gold);
  crescent.rotation.z=.22;
  crescent.position.set(-1.07,2.45,-.32);
  group.add(crescent);
  const star = new T.Shape();
  star.moveTo(0,1);
  star.quadraticCurveTo(.11,.14,.88,0);
  star.quadraticCurveTo(.12,-.13,0,-1);
  star.quadraticCurveTo(-.12,-.14,-.88,0);
  star.quadraticCurveTo(-.11,.14,0,1);
  const geometry = extrude(star,.23,.065);
  for (const [x,y,z,size,roll] of [[-2.15,2.06,0,.15,.08],[-2.59,1.28,.35,.105,-.08],[2.16,1.89,-.25,.13,-.04],[2.69,.61,.48,.12,.11],[2.52,-.65,.78,.105,-.1],[-1.79,.94,-1.59,.09,.04]]) {
    const mesh = new T.Mesh(geometry,gold);
    mesh.position.set(x,y,z);
    mesh.scale.setScalar(size);
    mesh.rotation.z=roll;
    group.add(mesh);
  }
}

export async function start(canvas, resetButton, signal) {
  let disposed=false;
  const cleanups=[];
  const listen=(target,type,handler,options)=>{target.addEventListener(type,handler,options);cleanups.push(()=>target.removeEventListener(type,handler,options));};
  const scene=new T.Scene();
  let frame=0, cloudTimer=0, observer;
  let renderer;
  function dispose(){
    if(disposed)return;disposed=true;
    clearTimeout(cloudTimer);cancelAnimationFrame(frame);observer?.disconnect();
    cleanups.splice(0).forEach(fn=>fn());
    const geometries=new Set(),materials=new Set(),textures=new Set();
    scene.traverse(o=>{if(o.geometry)geometries.add(o.geometry);if(o.material)for(const m of Array.isArray(o.material)?o.material:[o.material])materials.add(m);});
    for(const m of materials){for(const value of Object.values(m))if(value?.isTexture)textures.add(value);m.dispose();}
    geometries.forEach(g=>g.dispose());textures.forEach(t=>t.dispose());
    scene.traverse(o=>o.shadow?.dispose());renderer?.dispose();
  }
  try {
  renderer = new T.WebGLRenderer({canvas, alpha:true, antialias:true, powerPreference:'low-power'});
  renderer.setClearColor(0x000000,0);
  renderer.setPixelRatio(Math.min(devicePixelRatio,1.6));
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = T.VSMShadowMap;
  renderer.shadowMap.autoUpdate = false;
  renderer.shadowMap.needsUpdate = true;

  scene.add(new T.HemisphereLight(0xfffaf0,0xe1d7bf,2.05));
  const sun = new T.DirectionalLight(0xfff4e4,2.15);
  sun.position.set(-3.2,6.8,4.3);
  sun.castShadow = true;
  Object.assign(sun.shadow.camera,{left:-3.7,right:3.7,top:3.7,bottom:-3.7,near:.1,far:16});
  sun.shadow.camera.updateProjectionMatrix();
  sun.shadow.mapSize.set(1024,1024);
  sun.shadow.radius = 4;
  sun.shadow.blurSamples = 8;
  sun.shadow.bias = -.00035;
  sun.shadow.normalBias = .012;
  scene.add(sun);
  const rim = new T.DirectionalLight(0xffdfab,1.1);
  rim.position.set(3,3,-3);
  scene.add(rim);
  const set = new T.Group();
  const house = createCottage(T);
  house.position.set(.59,.04,-.56);
  house.rotation.y = -.24;
  house.scale.setScalar(1.10);
  scene.add(house);
  const cat = await loadCat(T,createOriginalCat);
  scene.add(cat.group);
  if(signal.aborted) {dispose();throw new DOMException('Unmounted','AbortError');}
  cat.group.position.set(-.35,.055,1.15);
  cat.group.scale.setScalar(1.12);
  cat.group.rotation.y = .25;

  const { island, clouds } = createIsland(T, 2.0);
  set.add(island,house,cat.group);
  for (const { orbit } of clouds) {
    batch(orbit);
    scene.add(orbit);
  }
  addSky(set);
  scene.add(set);
  const lamp = new T.PointLight(0xffbd56, 1.55, 2.9, 2);
  lamp.position.copy(house.localToWorld(new T.Vector3(.01,1.52,.32)));
  scene.add(lamp);
  batch(set,cat.group);
  const camera = new T.OrthographicCamera(-3,3,3,-3,.1,32);
  const stage = canvas.parentElement;
  const motion = matchMedia('(prefers-reduced-motion: reduce)');
  let yaw=initialYaw, pitch=initialPitch, targetYaw=yaw, targetPitch=pitch, velocity=0;
  let pointer=null, dragX=0, dragY=0, lastMove=0;
  let mouse=null, previousTime=0, lost=false;
  let trackAfter=performance.now() + (motion.matches ? 0 : innerWidth<=760 ? 550 : 1050);
  let lastCloudTime=performance.now();
  // Use the same framing for the default planet and both depth comparisons.
  const centerY = .30;
  function resize() {
    const {width,height} = stage.getBoundingClientRect();
    if (!width || !height) return;
    const aspect=width/height;
    const half=Math.max(2.96,3.16/aspect);
    Object.assign(camera,{top:half,bottom:-half,left:-half*aspect,right:half*aspect});
    camera.updateProjectionMatrix();
    renderer.setSize(width,height,false);
  }
  function draw() {
    camera.position.set(10*Math.sin(yaw)*Math.cos(pitch),centerY+10*Math.sin(pitch),10*Math.cos(yaw)*Math.cos(pitch));
    camera.lookAt(0,centerY,0);
    const renderStart=performance.now();
    renderer.shadowMap.needsUpdate = true;
    renderer.render(scene,camera);
    const elapsed=performance.now()-renderStart;
  }
  function wake() {
    clearTimeout(cloudTimer);
    if (!frame && !document.hidden && !lost && !disposed) {
      previousTime=performance.now();
      frame=requestAnimationFrame(tick);
    }
  }
  function tick(now) {
    frame=0;
    if (lost || disposed || document.hidden) return;
    const dt=clamp((now-previousTime)/1000,.001,.05);
    previousTime=now;
    if (motion.matches) velocity=0;
    if (pointer===null) {
      targetYaw+=velocity*dt;
      velocity*=Math.exp(-8.4*dt);
      if (Math.abs(velocity)<.008) velocity=0;
    }
    const follow=motion.matches ? 1 : 1-Math.exp(-20*dt);
    const yawStep=(targetYaw-yaw)*follow;
    yaw+=yawStep;
    if (!motion.matches) {
      // The camera orbits a fixed island. Partial camera compensation gives clouds
      // slower apparent rotation while orbiting, even across full turns and resets.
      const seconds=Math.max(0,now-lastCloudTime)/1000;
      for (const { orbit, speed } of clouds) {
        orbit.rotation.y+=speed*seconds + .32*yawStep;
      }
    }
    lastCloudTime=now;
    pitch+=(targetPitch-pitch)*follow;
    const moving=Math.abs(targetYaw-yaw)+Math.abs(targetPitch-pitch)>.0006 || !!velocity;
    const tracking=mouse && !motion.matches && pointer===null && !moving && now>=trackAfter;
    // Ease the neck towards neutral as the viewer moves around either shoulder.
    const facing=T.MathUtils.smoothstep(Math.cos(yaw-cat.group.rotation.y),.28,.78);
    const wantY=tracking ? mouse.x*.23*facing : 0;
    const wantX=tracking ? mouse.y*.13*facing : 0;
    const neck=motion.matches ? 1 : 1-Math.exp(-7*dt);
    cat.head.rotation.y+=(wantY-cat.head.rotation.y)*neck;
    cat.head.rotation.x+=(wantX-cat.head.rotation.x)*neck;
    cat.animation?.update(now,dt,{tracking:!!tracking && facing>.05,reduced:motion.matches,hidden:document.hidden});
    draw();
    const looking=Math.abs(wantY-cat.head.rotation.y)+Math.abs(wantX-cat.head.rotation.x)>.0008;
    if (moving || looking || cat.animation?.active || (mouse && now<trackAfter && !motion.matches)) frame=requestAnimationFrame(tick);
    else if (!motion.matches) cloudTimer=setTimeout(wake,65);
  }
  function reset() {
    velocity=0;
    targetYaw=yaw+wrap(initialYaw-yaw);
    targetPitch=initialPitch;
    wake();
  }
  listen(canvas,'keydown',event=>{
    const steps={ArrowLeft:[-.22,0],ArrowRight:[.22,0],ArrowUp:[0,.035],ArrowDown:[0,-.035]};
    if (event.key==='Home') reset();
    else if (steps[event.key]) {
      const [horizontal,vertical]=steps[event.key];
      velocity=0;
      targetYaw+=horizontal;
      targetPitch=clamp(targetPitch+vertical,.105,.33);
      wake();
    } else return;
    event.preventDefault();
  });
  listen(resetButton,'click',reset);
  listen(canvas,'pointerdown',event=>{
    if (pointer!==null || !event.isPrimary || event.button!==0) return;
    pointer=event.pointerId;
    if(event.pointerType==='touch')mouse=null;
    dragX=event.clientX;
    dragY=event.clientY;
    lastMove=performance.now();
    velocity=0;
    canvas.setPointerCapture(pointer);
    wake();
  });
  listen(canvas,'pointermove',event=>{
    if (pointer!==event.pointerId) return;
    const now=performance.now();
    const delta=-(event.clientX-dragX)*TAU/Math.max(260,stage.clientWidth*.88);
    targetYaw+=delta;
    if (event.pointerType!=='touch') targetPitch=clamp(targetPitch+(event.clientY-dragY)*.002,.105,.33);
    velocity=clamp(delta*1000/Math.max(12,now-lastMove),-2.6,2.6);
    dragX=event.clientX;
    dragY=event.clientY;
    lastMove=now;
    wake();
  });
  function release(event) {
    if (pointer!==event.pointerId) return;
    if (event.type!=='pointerup' || performance.now()-lastMove>90 || motion.matches) velocity=0;
    pointer=null;
    trackAfter=performance.now()+240;
    wake();
  }
  ['pointerup','pointercancel','lostpointercapture'].forEach(type=>listen(canvas,type,release));
  listen(window,'pointermove',event=>{
    mouse=event.pointerType==='mouse' ? {x:clamp(2*event.clientX/innerWidth-1,-1,1),y:clamp(2*event.clientY/innerHeight-1,-1,1)} : null;
    if (mouse || cat.head.rotation.x || cat.head.rotation.y) wake();
  },{passive:true});
  listen(document,'pointerout',event=>{ if (!event.relatedTarget) { mouse=null; wake(); } });
  listen(motion,'change',()=>{lastCloudTime=performance.now(); velocity=0; wake();});
  listen(document,'visibilitychange',()=>{
    lastCloudTime=performance.now();
    cat.animation?.resetClock(lastCloudTime);
    if (document.hidden) { clearTimeout(cloudTimer); cancelAnimationFrame(frame); frame=0; velocity=0; } else wake();
  });
  listen(canvas,'webglcontextlost',()=>{lost=true; clearTimeout(cloudTimer); cancelAnimationFrame(frame);});
  observer=new ResizeObserver(()=>{resize(); wake();});observer.observe(stage);
  resize();
  draw();
  listen(signal,'abort',dispose,{once:true});
  wake();
  return dispose;
  } catch(error) {dispose();throw error;}
}

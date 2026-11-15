// All dimensions are local to the island. The garden surface is at y = 0.
export function createIsland(THREE, lowerDepth = 2.0) {
  const island = new THREE.Group();
  island.name = '浮岛花园';
  let seed = 173957;
  const random = () => ((seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0) / 4294967296);
  const tau = Math.PI * 2;
  const radius = 2.45;
  const originalDepth = 1.66;
  const materials = new Map();
  const ball = new THREE.SphereGeometry(1, 16, 12);

  function clay(color) {
    if (!materials.has(color)) {
      materials.set(color, new THREE.MeshStandardMaterial({ color, roughness: 0.92 }));
    }
    return materials.get(color);
  }

  function mesh(parent, geo, mat, x = 0, y = 0, z = 0) {
    const object = new THREE.Mesh(geo, mat);
    object.position.set(x, y, z);
    object.castShadow = object.receiveShadow = true;
    parent.add(object);
    return object;
  }

  function oval(parent, color, x, y, z, sx, sy, sz) {
    const obj = mesh(parent, ball, clay(color), x, y, z);
    obj.scale.set(sx, sy, sz);
    return obj;
  }

  function rod(a, b, r, color, tip = r) {
    const start = new THREE.Vector3(...a);
    const end = new THREE.Vector3(...b);
    const delta = end.clone().sub(start);
    const obj = mesh(island, new THREE.CylinderGeometry(tip, r, delta.length(), 10), clay(color));
    obj.position.copy(start).lerp(end, 0.5);
    obj.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), delta.normalize());
  }

  function texture(width, height, paint) {
    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    paint(canvas.getContext('2d'));
    const map = new THREE.CanvasTexture(canvas);
    map.colorSpace = THREE.SRGBColorSpace;
    return map;
  }

  // Evaluate the pattern in sphere coordinates so it closes across the longitude seam.
  const earthTexture = texture(768, 256, ctx => {
    const pixels = ctx.createImageData(768, 256);
    const sea = [83, 133, 109], shore = [133, 157, 97], land = [160, 176, 105];
    for (let j = 0; j < 256; ++j) {
      const lat = j / 255 * Math.PI / 2;
      for (let i = 0; i < 768; ++i) {
        const lon = i / 767 * tau;
        const x = Math.cos(lat) * Math.cos(lon), y = Math.sin(lat), z = Math.cos(lat) * Math.sin(lon);
        const f = Math.sin(5*x + Math.sin(6*y)) + Math.sin(5*z - 7*y)
          + 0.45*Math.sin(12*y + 6*x + 6*z) + 0.2*Math.cos(14*x - 17*z);
        const color = f > 0.58 ? land : f > 0.43 ? shore : sea;
        const mottling = Math.sin(12*x + 4*z) * 2.5 + 4 * Math.sin(10*z + 14*y) + (random()-0.5) * 8;
        const k = (j*768+i)*4;
        for (let c = 0; c < 3; c++) pixels.data[k+c] = color[c] + mottling;
        pixels.data[k+3] = 255;
      }
    }
    ctx.putImageData(pixels, 0, 0);
  });
  earthTexture.wrapS = THREE.RepeatWrapping;
  const grassTexture = texture(512, 512, ctx => {
    ctx.fillStyle = '#b2bd76';
    ctx.fillRect(0, 0, 512, 512);
    for (let i = 0; i < 9000; i++) {
      ctx.fillStyle = i % 3 ? 'rgba(78,107,49,.13)' : 'rgba(255,249,176,.30)';
      ctx.beginPath();
      const x = random()*512, y = random()*512;
      ctx.ellipse(x, y, 0.3+random()*1.8, 0.35+random()*0.65, random()*Math.PI, 0, tau);
      ctx.fill();
    }
  });

  // Each strip winds from its upper inner ring outward and down.
  function skin(rowCount, sample, map) {
    const count = 112, positions = [], uv = [], indices = [];
    for (let j = 0; j <= rowCount; j++) {
      for (let i = 0; i <= count; i++) {
        const pt = sample(i/count*tau, j/rowCount);
        positions.push(pt[0], pt[1], pt[2]);
        uv.push(pt[3], pt[4]);
        if (i < count && j < rowCount) {
          const a = j*(count+1)+i, b = a+count+1;
          indices.push(a, a+1, b, a+1, b+1, b);
        }
      }
    }
    const geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3));
    geo.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
    geo.setIndex(indices);
    geo.computeVertexNormals();
    const mat = new THREE.MeshStandardMaterial({ map, roughness: 1 });
    return mesh(island, geo, mat);
  }

  const ripple = a => 1 + 0.012*Math.sin(5*a+1) + 0.008*Math.cos(9*a);
  skin(28, (a, t) => {
    const phi = t*Math.PI/2, r = radius * Math.cos(phi) * ripple(a);
    // The original shoulder meets both the grass skirt and the side flowers.
    // Introduce the extra depth below that seam, leaving all upper vertices intact.
    const latitude = Math.sin(phi);
    const blend = THREE.MathUtils.smoothstep(latitude, 0.33, 0.78);
    const height = -0.10 - latitude * (originalDepth + (lowerDepth - originalDepth) * blend);
    return [r*Math.cos(a), height, r*Math.sin(a), a/tau, 1-t];
  }, earthTexture);
  skin(18, (a, t) => {
    let r, h;
    if (t <= 0.72) {
      const top = t/0.72;
      r = radius * top * ripple(a);
      h = 0.015 - 0.075*top**5 + Math.sin(5*a)*0.009*top;
    } else {
      const side = (t-0.72)/0.28;
      const drips = 0.16 + 0.24*((1+Math.sin(9*a+Math.sin(3*a)))/2)**3
        + 0.13*((1+Math.cos(13*a-0.5))/2)**5;
      h = -0.06 - side*drips;
      const phi = Math.asin(Math.max(0,(-h-0.10)/originalDepth));
      r = (radius*Math.cos(phi)*ripple(a)) + 0.05*Math.sin(side*Math.PI)**0.7 + 0.008;
    }
    return [r*Math.cos(a), h, r*Math.sin(a), 0.5+r*Math.cos(a)/5.1, 0.5+r*Math.sin(a)/5.1];
  }, grassTexture);

  function cloud(x, y, z, scale, yaw, speed) {
    const orbit = new THREE.Group();
    const p = new THREE.Group();
    orbit.add(p);
    p.position.set(x, y, z);
    p.rotation.y = yaw;
    p.scale.setScalar(scale);
    const puffs = [[-0.42,0.00,0.03,0.23],[-0.18,0.09,-0.02,0.28],[0.09,0.15,0,0.32],
      [0.36,0.06,-0.03,0.23],[0.52,0.01,0.04,0.15],[0,-0.02,0.08,0.27]];
    for (const [px, py, pz, s] of puffs) {
      const puff = oval(p,'#fff3d9',px,py,pz,s,s*0.9,s*0.78);
      // Keep moving clouds out of the cached VSM shadow pass, including receivers.
      puff.castShadow = puff.receiveShadow = false;
    }
    return { orbit, speed };
  }
  const clouds = [
    cloud(-2.46, -0.49, 1.36, 0.91, -0.55, 0.016),
    cloud(1.73, -0.82, 2.00, 1.14, 0.44, 0.020),
    cloud(-0.47, -0.96, -2.55, 0.72, -0.26, 0.018)
  ];

  // Six slightly ridged blades form each rosette; the blade arches radially outward.
  const blade = new THREE.BufferGeometry();
  const verts = [], idx = [];
  for (let i = 0; i <= 8; i++) {
    const t = i/8;
    const w = 0.24*Math.sin(t*Math.PI)**0.76;
    const y = 0.70*t + 0.25*Math.sin(Math.PI*t);
    verts.push(-w,y,0.73*t*t, 0,y+0.055*Math.sin(Math.PI*t),0.73*t*t-0.025, w,y,0.73*t*t);
    if(i<8) {
      const n = i*3;
      idx.push(n,n+3,n+1,n+1,n+3,n+4,n+1,n+4,n+2,n+2,n+4,n+5);
    }
  }
  blade.setAttribute('position',new THREE.Float32BufferAttribute(verts,3));
  blade.setIndex(idx);
  blade.computeVertexNormals();
  const leafMats = ['#6a9555','#9caf61','#82a864'].map(color =>
    new THREE.MeshStandardMaterial({ color, roughness: 0.9, side: THREE.DoubleSide }));
  function rosette(x, y, z, s) {
    const rotation = random()*tau;
    for(let i=0; i<6; i++) {
      const b = mesh(island,blade,leafMats[i%3],x,y,z);
      b.scale.set(s,s*(0.76+random()*0.35),s);
      b.rotation.y = rotation + i*tau/6;
    }
  }

  function daisy(x,y,z,s,yaw=0,pitch=-0.38) {
    const bloom = new THREE.Group();
    island.add(bloom);
    bloom.position.set(x,y,z);
    bloom.rotation.order = 'YXZ';
    bloom.rotation.set(pitch,yaw,0,'YXZ');
    bloom.scale.setScalar(s);
    for(let i=0; i<6; i++) {
      const a=i*tau/6;
      const petal = oval(bloom,'#fff5da',0.17*Math.cos(a),0.17*Math.sin(a),0,0.14,0.095,0.071);
      petal.rotation.z = a;
    }
    oval(bloom,'#e7ad3e',0,0,0.066,0.096,0.096,0.067);
  }
  const plants = [
    [-1.59,1.27,0.46],[-1.28,1.49,0.31],[1.64,1.14,0.46],[1.90,0.92,0.35],[-2.03,-0.50,0.55],
    [2.02,-0.63,0.36],[1.47,-1.43,0.45],[-0.13,-1.94,0.60],[-0.82,-1.82,0.34],[-1.72,-1.15,0.5]
  ];
  for (const [x,z,s] of plants) rosette(x,0,z,s);
  daisy(-1.56,0.27,1.32,0.68);
  daisy(-1.30,0.18,1.53,0.43,-0.27);
  daisy(1.65,0.26,1.20,0.68,0.17);
  daisy(1.87,0.20,0.99,0.46,0.28);
  daisy(-0.2,0.32,-2.02,0.69,Math.PI);
  daisy(-0.70,0.22,-1.90,0.46,Math.PI-0.4);
  daisy(-0.62,-0.43,2.32,0.68,-0.21,-0.06);
  daisy(1.88,-0.27,-1.48,0.50,2.1,-0.12);

  const treeX = -1.49, treeZ = -0.57;
  rod([treeX,0,treeZ],[treeX+0.05,1.03,treeZ],0.12,'#967043',0.083);
  rod([treeX,0.57,treeZ],[treeX-0.30,0.98,treeZ+0.08],0.073,'#967043',0.047);
  rod([treeX+0.02,0.68,treeZ],[treeX+0.28,1.04,treeZ-0.13],0.08,'#967043',0.047);
  const crowns = [[0,1.38,0,0.48,'#87a451'],[-0.29,1.12,0.07,0.32,'#76974f'],
    [0.28,1.17,-0.1,0.33,'#718f4d'],[0.01,1.09,0.23,0.31,'#88a255']];
  for (const [dx,y,dz,s,c] of crowns) {
    oval(island,c,treeX+dx,y,treeZ+dz,s,s*1.02,s*0.86);
    for(let i=0;i<7;i++) {
      const a=i*tau/7+0.26, r=s*0.67;
      oval(island,c,treeX+dx+r*Math.cos(a),y+s*(0.03+0.16*Math.sin(i*2)),treeZ+dz+r*0.85*Math.sin(a),
        s*0.42,s*0.45,s*0.39);
    }
  }
  rosette(treeX+0.13,0,treeZ+0.18,0.39);

  function roundedBrick(w,h,d) {
    const x=w/2-0.028, y=h/2-0.028, r=0.052;
    const shape = new THREE.Shape();
    shape.moveTo(-x+r,-y);
    shape.lineTo(x-r,-y); shape.quadraticCurveTo(x,-y,x,-y+r);
    shape.lineTo(x,y-r); shape.quadraticCurveTo(x,y,x-r,y);
    shape.lineTo(-x+r,y); shape.quadraticCurveTo(-x,y,-x,y-r);
    shape.lineTo(-x,-y+r); shape.quadraticCurveTo(-x,-y,-x+r,-y);
    const geo = new THREE.ExtrudeGeometry(shape,{
      depth:d-0.056,bevelEnabled:true,bevelSize:0.028,bevelThickness:0.028,bevelSegments:3,curveSegments:4
    });
    geo.translate(0,0,-(d-0.056)/2);
    return geo;
  }
  function brick(w,h,d,x,y,z,angle,base,ink,label) {
    const group = new THREE.Group();
    island.add(group);
    group.position.set(x,y,z);
    group.rotation.y=angle;
    mesh(group,roundedBrick(w,h,d),clay(base));
    const map=texture(256,128,ctx=>{
      ctx.fillStyle=ink;
      ctx.strokeStyle=ink;
      if(label === 'cloud') {
        ctx.lineWidth=8; ctx.lineJoin='round'; ctx.lineCap='round';
        ctx.beginPath();
        ctx.moveTo(92,89);ctx.bezierCurveTo(55,89,57,52,85,52);
        ctx.bezierCurveTo(96,20,143,16,156,49);
        ctx.bezierCurveTo(190,43,201,87,167,89);ctx.closePath();ctx.stroke();
      } else {
        ctx.textAlign='center'; ctx.textBaseline='middle'; ctx.font='bold 80px Arial, sans-serif';
        ctx.fillText(label,128,68);
      }
    });
    // Alpha-tested ink stays on the rounded block as the island turns.
    const inkMaterial = new THREE.MeshStandardMaterial({map,alphaTest:0.3,roughness:0.92});
    const print = mesh(group,new THREE.PlaneGeometry(w*0.92,h*0.86),inkMaterial,0,0,d/2+0.002);
    print.castShadow=false;
  }
  brick(0.66,0.40,0.55,-1.63,0.20,0.62,0.11,'#477952','#ffebbb','cloud');
  brick(0.61,0.40,0.52,-1.62,0.60,0.62,-0.025,'#439481','#fff0c8','API');
  brick(0.50,0.38,0.46,-1.58,0.99,0.60,0.12,'#eed19a','#825329','{ }');

  // Stepping stones lead from the front garden to the little desk.
  const path = [[0.39,1.96,0.24],[0.62,1.66,0.23],[0.73,1.35,0.22],[0.82,1.10,0.18],[0.87,0.85,0.17]];
  for(const [x,z,s] of path) {
    const stone=oval(island,'#dcc7a3',x,0.037,z,s,0.06,s*0.69);
    stone.rotation.y=random()*1.1;
  }
  const posts=[];
  for(let i=0;i<7;i++) {
    const a=-0.90+1.48*i/6;
    const x=2.15*Math.cos(a),z=2.15*Math.sin(a);
    posts.push([x,z]);
    rod([x,-0.012,z],[x,0.40,z],0.050,'#b1824e',0.048);
    oval(island,'#bb8b55',x,0.40,z,0.051,0.035,0.051);
  }
  for(let i=1;i<posts.length;i++) {
    for(const [h,r] of [[0.14,0.035],[0.32,0.047]]) {
      rod([posts[i-1][0],h,posts[i-1][1]],[posts[i][0],h,posts[i][1]],r,'#ca995d');
    }
  }
  return { island, clouds };
}

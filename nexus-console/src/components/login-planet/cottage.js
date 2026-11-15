// A little timber-frame workroom. Local +Z is the open front, +Y is up.
export function createCottage(THREE) {
  const house = new THREE.Group();
  house.name = 'Garden workroom';
  const materials = new Map();
  const geometry = new Map();
  function clay(color) {
    if (!materials.has(color)) {
      materials.set(color, new THREE.MeshStandardMaterial({ color, roughness: 0.86 }));
    }
    return materials.get(color);
  }
  function piece(shape, color, x, y, z) {
    const part = new THREE.Mesh(shape, clay(color));
    part.position.set(x, y, z);
    part.castShadow = true;
    part.receiveShadow = true;
    house.add(part);
    return part;
  }
  function cached(key, build) {
    if (!geometry.has(key)) geometry.set(key, build());
    return geometry.get(key);
  }
  function board(x, y, z, w, h, d, color, bevel = 0.012) {
    const radius = Math.min(bevel, w * 0.19, h * 0.19, d * 0.19);
    const geo = cached(['box', w, h, d, radius].join('/'), () => {
      const a = w / 2 - radius, b = h / 2 - radius;
      const outline = new THREE.Shape();
      outline.moveTo(-a, -b);
      outline.lineTo(a, -b);
      outline.lineTo(a, b);
      outline.lineTo(-a, b);
      outline.closePath();
      const result = new THREE.ExtrudeGeometry(outline, {
        depth: d - 2 * radius, bevelEnabled: true, bevelSegments: 2,
        bevelThickness: radius, bevelSize: radius, steps: 1
      });
      result.translate(0, 0, -d / 2 + radius);
      return result;
    });
    return piece(geo, color, x, y, z);
  }
  const sphere = new THREE.SphereGeometry(1, 14, 10);
  function oval(x, y, z, rx, ry, rz, color) {
    const part = piece(sphere, color, x, y, z);
    part.scale.set(rx, ry, rz);
    return part;
  }
  function stick(a, b, radius, color) {
    const start = new THREE.Vector3(...a), end = new THREE.Vector3(...b);
    const delta = end.sub(start);
    const mid = start.clone().addScaledVector(delta, 0.5);
    const rod = piece(cached('rod', () => new THREE.CylinderGeometry(1, 1, 1, 12)), color, ...mid.toArray());
    rod.scale.set(radius, delta.length(), radius);
    rod.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), delta.normalize());
    return rod;
  }
  const timber = '#ad7444', honey = '#cb9457', grain = '#a97444';
  const spruce = '#376e63', teal = '#397b6b';
  const plaster = '#ffe0a9', shade = '#e7bb81', chalk = '#ffedc8';

  // A continuous rear gable, a half-timbered side and a cutaway front.
  const gable = new THREE.Shape();
  gable.moveTo(-0.88, 0); gable.lineTo(0.88, 0); gable.lineTo(0.88, 1.42);
  gable.lineTo(0, 2.12); gable.lineTo(-0.88, 1.42); gable.closePath();
  piece(new THREE.ExtrudeGeometry(gable, {
    depth: 0.10, bevelEnabled: true, bevelThickness: 0.015, bevelSize: 0.015, bevelSegments: 2
  }), plaster, 0, 0.04, -0.68);
  for (const sign of [-1, 1]) {
    board(sign * 0.855, 0.73, -0.06, 0.11, 1.38, 1.29, plaster);
    for (const z of [-0.665, 0.60]) board(sign * 0.853, 0.74, z, 0.13, 1.43, 0.13, honey);
    board(sign * 0.898, 0.19, -0.03, 0.06, 0.12, 1.33, honey);
    board(sign * 0.895, 1.35, -0.03, 0.07, 0.11, 1.33, timber);
  }
  board(0, 0.05, 0.03, 1.93, 0.10, 1.50, honey, 0.025);
  for (const x of [-0.58, -0.21, 0.16, 0.53]) {
    board(x, 0.103, 0.03, 0.009, 0.006, 1.43, grain);
  }
  for (const y of [0.23, 1.35]) board(0, y, -0.71, 1.87, 0.105, 0.065, honey);
  board(0.04, 1.72, -0.716, 0.083, 0.65, 0.055, honey);
  // Slim rear braces and small uneven stone courses finish the uncut side.
  for (const sign of [-1, 1]) {
    stick([sign * 0.84, 1.34, -0.724], [sign * 0.38, 1.73, -0.724], 0.036, timber);
    for (let i = 0; i < 3; i++) {
      board(sign * (0.49 + 0.12 * (i % 2)), 0.34 + 0.08 * i, -0.711,
        0.19 - i * 0.017, 0.045, 0.035, shade);
    }
  }

  // Side window: warm arched pane, solid timber sill and inset muntins.
  function arch(width, height, depth, color, x, z, facing) {
    const r = width / 2, spring = height - r;
    const path = new THREE.Shape();
    path.moveTo(-r, 0); path.lineTo(r, 0); path.lineTo(r, spring);
    path.absarc(0, spring, r, 0, Math.PI, false); path.closePath();
    const pane = piece(new THREE.ExtrudeGeometry(path, {
      depth, bevelEnabled: true, bevelSize: 0.012, bevelThickness: 0.01, bevelSegments: 2,
      curveSegments: 12
    }), color, x, 0.52, z);
    pane.rotation.y = facing * Math.PI / 2;
    return pane;
  }
  for (const side of [-1, 1]) {
    arch(0.59, 0.72, 0.03, timber, side * 0.91, -0.06, side);
    const pane = arch(0.47, 0.60, 0.015, '#f9c467', side * 0.951, -0.06, side);
    pane.position.y += 0.06;
    board(side * 0.98, 0.86, -0.06, 0.045, 0.59, 0.038, honey);
    board(side * 0.98, 0.86, -0.06, 0.045, 0.042, 0.47, honey);
    board(side * 0.96, 0.51, -0.06, 0.16, 0.068, 0.69, honey);
  }

  // Each shingle has a rounded lower edge; successive courses overlap.
  const pitch = Math.atan2(0.86, 1.09);
  const roofY = x => 2.18 - Math.tan(pitch) * Math.abs(x);
  const tileColors = ['#467b69', '#528773', '#4e8270', '#5c8c74', '#407768'];
  for (const side of [-1, 1]) {
    const roof = board(side * 0.54, roofY(0.54), -0.03, 1.42, 0.085, 1.66, spruce, 0.025);
    roof.rotation.z = -side * pitch;
    for (let row = 0; row < 4; row++) {
      const x = 0.18 + row * 0.276;
      for (let col = 0; col < 6; col++) {
        const z = -0.725 + col * 0.28 + (row % 2 ? 0.008 : -0.008);
        const color = tileColors[(row * 7 + col * 3 + (side > 0 ? 1 : 2)) % tileColors.length];
        const shingle = board(side * x, roofY(x) + 0.05 + (3 - row) * 0.005,
          z, 0.43, 0.085, 0.283, color, 0.039);
        shingle.rotation.z = -side * (pitch - 0.04);
      }
    }
    for (const z of [-0.85, 0.80]) {
      const trim = board(side * 0.535, roofY(0.535) - 0.03, z, 1.43, 0.129, 0.089, honey, 0.022);
      trim.rotation.z = -side * pitch;
    }
  }
  for (let i = 0; i < 6; i++) {
    const ridge = board(0, 2.24, -0.727 + i * 0.284, 0.15, 0.11, 0.30, spruce, 0.042);
    ridge.rotation.z = 0.03 * Math.sin(i);
  }
  // Workbench and real 3D monitor, including the raised code mark.
  for (const x of [-0.62, 0.62]) {
    for (const z of [-0.31, 0.33]) board(x, 0.36, z, 0.075, 0.52, 0.082, timber);
  }
  board(0, 0.64, 0, 1.49, 0.095, 0.86, honey, 0.022);
  board(0.15, 0.701, -0.105, 0.35, 0.037, 0.19, chalk);
  board(0.15, 0.785, -0.13, 0.073, 0.17, 0.065, chalk);
  board(0.15, 1.08, -0.10, 0.75, 0.49, 0.065, chalk, 0.028);
  board(0.15, 1.085, -0.06, 0.655, 0.392, 0.026, '#235653', 0.023);
  const symbol = '#ffedbb', face = -0.035;
  for (const sign of [-1, 1]) {
    const outside = 0.15 + sign * 0.165, inside = 0.15 + sign * 0.097;
    stick([inside, 1.146, face], [outside, 1.086, face], 0.015, symbol);
    stick([outside, 1.086, face], [inside, 1.029, face], 0.015, symbol);
  }
  stick([0.119, 1.004, face], [0.18, 1.163, face], 0.014, symbol);
  board(0.10, 0.707, 0.225, 0.43, 0.034, 0.14, chalk);
  for (let i = 0; i < 3; i++) {
    board(0.1, 0.726, 0.181 + i * 0.041, 0.33, 0.005, 0.012, '#c9b993');
  }
  oval(0.424, 0.702, 0.225, 0.051, 0.033, 0.071, chalk);
  // Open topped glazed cup with a loop handle.
  const cup = piece(new THREE.LatheGeometry([
    new THREE.Vector2(0.048, 0), new THREE.Vector2(0.066, 0.113),
    new THREE.Vector2(0.055, 0.116), new THREE.Vector2(0.049, 0.035)
  ], 20), chalk, -0.43, 0.686, 0.125);
  oval(cup.position.x, 0.777, cup.position.z, 0.054, 0.005, 0.054, '#865538');
  const handle = piece(new THREE.TorusGeometry(0.036, 0.012, 8, 16), chalk, -0.362, 0.748, 0.125);
  handle.scale.y = 1.1;
  // Curved chair silhouette and little tapered-looking timber legs.
  for (const x of [-0.13, 0.13]) {
    for (const z of [0.39, 0.63]) {
      stick([x * 1.18 - 0.09, 0.11, z + (z > 0.5 ? 0.033 : -0.033)],
        [x - 0.09, 0.39, z], 0.032, timber);
    }
  }
  board(-0.09, 0.405, 0.51, 0.39, 0.078, 0.36, teal, 0.03);
  for (const x of [-0.20, 0.02]) board(x, 0.54, 0.653, 0.045, 0.24, 0.045, timber);
  board(-0.09, 0.665, 0.66, 0.36, 0.246, 0.073, teal, 0.041);
  // Books, a wall shelf and a brass bell lamp.
  board(-0.51, 1.091, -0.395, 0.51, 0.055, 0.27, honey);
  for (let i = 0; i < 3; i++) {
    const book = board(-0.52 + i % 2 * 0.025, 1.15 + i * 0.064, -0.392,
      0.34 - i * 0.027, 0.06, 0.196, ['#658066', '#ecd39a', '#b57e4c'][i]);
    book.rotation.y = (i - 1) * 0.055;
  }
  stick([0.01, 2.07, 0.32], [0.01, 1.76, 0.32], 0.016, timber);
  piece(new THREE.LatheGeometry([
    new THREE.Vector2(0.012, 0.144), new THREE.Vector2(0.075, 0.123),
    new THREE.Vector2(0.125, 0.077), new THREE.Vector2(0.157, 0.018),
    new THREE.Vector2(0.154, 0), new THREE.Vector2(0.128, 0.019),
    new THREE.Vector2(0.01, 0.121)
  ], 22), '#b7823e', 0.01, 1.615, 0.32);
  const bulb = oval(0.01, 1.608, 0.32, 0.074, 0.077, 0.074, '#ffdf8e');
  bulb.material.emissive.set('#ffbd4e');
  bulb.material.emissiveIntensity = 0.39;

  // A trailing sprig wraps across the roof and down past the right window.
  const vine = [[0.04, 2.285, -0.39], [0.25, 2.10, -0.32], [0.47, 1.95, -0.17],
    [0.69, 1.78, -0.10], [0.91, 1.59, 0.01], [1.09, 1.43, 0.1], [1.10, 1.24, 0.18]];
  for (let i = 0; i < vine.length - 1; i++) {
    stick(vine[i], vine[i + 1], 0.017, '#678654');
    for (const side of [-1, 1]) {
      const [x, y, z] = vine[i];
      const leaf = oval(x + 0.035, y + 0.046, z + 0.07 * side, 0.057, 0.032, 0.106,
        (i + side) % 2 ? '#80a165' : '#648a5b');
      leaf.rotation.y = side * 0.48;
      leaf.rotation.z = -pitch;
    }
  }
  return house;
}

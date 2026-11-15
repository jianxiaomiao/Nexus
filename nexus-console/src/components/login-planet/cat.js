// Original sculpt for the Nexus garden. Y is up, +Z is the kitten's front.
export function createCat(THREE) {
  const group = new THREE.Group();
  group.name = 'Nexus tabby';
  const head = new THREE.Group();
  head.name = 'Neck pivot';
  head.position.set(0, 0.81, 0.055);
  group.add(head);
  const sphere = new THREE.SphereGeometry(1, 36, 28);
  const matte = (color, extras = {}) => new THREE.MeshStandardMaterial({ color, roughness: 0.83, ...extras });
  const ivory = matte(0xffedce);
  const ink = matte(0x563527);
  const whisker = matte(0xc09067);
  const gold = matte(0xbd894f, { roughness: 0.55 });
  const iris = matte(0x321f16, { roughness: 0.25 });
  const glint = new THREE.MeshBasicMaterial({ color: 0xfff8de });
  const blush = matte(0xe18b79);

  function ellipsoid(parent, xyz, radii, material) {
    const mesh = new THREE.Mesh(sphere, material);
    mesh.position.set(...xyz);
    mesh.scale.set(...radii);
    mesh.castShadow = true;
    mesh.receiveShadow = true;
    parent.add(mesh);
    return mesh;
  }

  function stroke(parent, coordinates, radius, material) {
    const path = new THREE.CatmullRomCurve3(coordinates.map(p => new THREE.Vector3(...p)));
    const mesh = new THREE.Mesh(new THREE.TubeGeometry(path, 10, radius, 6, false), material);
    parent.add(mesh);
    return mesh;
  }

  const smooth = (low, high, t) => THREE.MathUtils.smoothstep(t, low, high);
  const mix = (a, b, weight) => a + (b - a) * weight;
  const honey = [240, 167, 86], ginger = [192, 110, 52], milk = [255, 239, 211];

  // Paint the markings in sphere coordinates so the dorsal bands wrap around the body.
  function coat(kind) {
    const canvas = document.createElement('canvas');
    canvas.width = 384;
    canvas.height = 192;
    const ctx = canvas.getContext('2d');
    const pixels = ctx.createImageData(canvas.width, canvas.height);
    for (let row = 0; row < canvas.height; row++) {
      const latitude = (row + 0.5) * Math.PI / canvas.height;
      const y = Math.cos(latitude), ring = Math.sin(latitude);
      for (let col = 0; col < canvas.width; col++) {
        const longitude = (col + 0.5) * 2 * Math.PI / canvas.width;
        const x = -Math.cos(longitude) * ring, z = Math.sin(longitude) * ring;
        const front = smooth(0.03, 0.29, z);
        let white = 0, band = 0;
        if (kind === 'head') {
          // Soft lower mask and a narrow milk-coloured blaze above the nose.
          const boundary = -0.24 + 0.46 * Math.exp(-Math.pow(x / 0.28, 2)) - 0.13 * x * x;
          white = (1 - smooth(boundary - 0.014, boundary + 0.016, y)) * smooth(-0.43, 0.20, z);
          // Five tapered brow markings follow the dome towards the back of the head.
          const side = Math.abs(x);
          const browStart = 0.35 + 0.46 * side;
          const width = (0.030 + 0.030 * smooth(0.43, 0.85, y)) * smooth(browStart, browStart + 0.18, y);
          const comb = Math.min(side, Math.abs(side - (0.13 + 0.12 * y)), Math.abs(side - (0.30 + 0.14 * y)));
          band = (1 - smooth(width, width + 0.009, comb)) * smooth(browStart, browStart + 0.12, y);
          const cheekY = -0.19 + 0.29 * (1 - z);
          const cheekDistance = Math.min(Math.abs(y - cheekY), Math.abs(y - cheekY - 0.21));
          const cheekWidth = 0.057 * smooth(0.47, 0.84, side);
          band = Math.max(band, (1 - smooth(cheekWidth, cheekWidth + 0.009, cheekDistance)) * smooth(0.47, 0.80, side));
        } else if (kind === 'body') {
          const bibWidth = 0.38 + 0.19 * smooth(-0.8, 0.65, y);
          white = (1 - smooth(bibWidth - 0.03, bibWidth + 0.03, Math.abs(x))) * front;
          const arch = y + 0.21 * z + 0.09 * x * x;
          const distance = Math.min(Math.abs(arch - 0.29), Math.abs(arch + 0.18), Math.abs(arch + 0.61));
          band = (1 - smooth(0.052, 0.077, distance)) * (1 - smooth(0.12, 0.72, z));
        } else {
          const distance = Math.min(Math.abs(y - 0.24 - 0.13 * z), Math.abs(y + 0.31 - 0.12 * z));
          band = (1 - smooth(0.11, 0.15, distance)) * smooth(-0.90, -0.24, z);
          white = 1 - smooth(-0.78, -0.70, y);
        }
        const index = 4 * (row * canvas.width + col);
        for (let c = 0; c < 3; c++) pixels.data[index + c] = mix(mix(honey[c], ginger[c], band * 0.86), milk[c], white);
        pixels.data[index + 3] = 255;
      }
    }
    ctx.putImageData(pixels, 0, 0);
    const texture = new THREE.CanvasTexture(canvas);
    texture.colorSpace = THREE.SRGBColorSpace;
    texture.wrapS = THREE.RepeatWrapping;
    return matte(0xffffff, { map: texture });
  }

  const torso = coat('body'), stripedLeg = coat('leg');
  ellipsoid(group, [0, 0.48, -0.04], [0.36, 0.48, 0.31], torso);
  for (const sign of [-1, 1]) {
    const thigh = ellipsoid(group, [sign * 0.24, 0.245, -0.06], [0.20, 0.235, 0.23], stripedLeg);
    thigh.rotation.z = sign * -0.20;
    ellipsoid(group, [sign * 0.27, 0.115, 0.060], [0.15, 0.105, 0.205], ivory);
    const leg = ellipsoid(group, [sign * 0.145, 0.315, 0.203], [0.108, 0.273, 0.114], stripedLeg);
    leg.rotation.z = -sign * 0.11;
    ellipsoid(group, [sign * 0.128, 0.097, 0.272], [0.124, 0.094, 0.145], ivory);
    for (const toe of [-0.035, 0.035]) {
      const x = sign * 0.128 + toe;
      stroke(group, [[x, 0.132, 0.396], [x, 0.111, 0.411], [x, 0.093, 0.412]], 0.0028, whisker);
    }
  }

  ellipsoid(head, [0, 0.295, 0.035], [0.487, 0.393, 0.379], coat('head'));
  // Rounded leaf-shaped ears with an inset rose inner surface.
  function ear(sign) {
    const vertices = [], colors = [], indices = [];
    const rings = 20, sides = 32;
    const outer = new THREE.Color(0xf1aa64), inner = new THREE.Color(0xeea08d);
    const sample = new THREE.Color();
    for (let j = 0; j <= rings; j++) {
      const t = j / rings;
      const taper = Math.pow(1 - t, 0.68);
      for (let i = 0; i <= sides; i++) {
        const angle = i * 2 * Math.PI / sides;
        const across = Math.cos(angle), towards = Math.sin(angle);
        vertices.push(sign * (0.285 + 0.072 * t) + across * 0.153 * taper,
          0.502 + 0.317 * t, -0.018 - 0.041 * t + 0.104 * taper * towards);
        const trim = smooth(0.12, 0.23, t) * (1 - smooth(0.78, 0.92, t));
        const inset = smooth(0.48, 0.69, towards) * trim;
        sample.copy(outer).lerp(inner, inset);
        colors.push(sample.r, sample.g, sample.b);
      }
    }
    for (let j = 0; j < rings; j++) for (let i = 0; i < sides; i++) {
      const a = j * (sides + 1) + i, b = a + sides + 1;
      indices.push(a, b, a + 1, a + 1, b, b + 1);
    }
    const geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.Float32BufferAttribute(vertices, 3));
    geo.setAttribute('color', new THREE.Float32BufferAttribute(colors, 3));
    geo.setIndex(indices);
    geo.computeVertexNormals();
    const mesh = new THREE.Mesh(geo, matte(0xffffff, { vertexColors: true }));
    mesh.castShadow = true;
    head.add(mesh);
  }

  for (const sign of [-1, 1]) {
    ear(sign);
    const eye = new THREE.Group();
    eye.position.set(sign * 0.191, 0.359, 0.374);
    eye.rotation.y = sign * 0.30;
    eye.rotation.z = sign * 0.065;
    head.add(eye);
    ellipsoid(eye, [0, 0, 0], [0.089, 0.103, 0.039], gold);
    ellipsoid(eye, [0, 0.007, 0.019], [0.079, 0.088, 0.037], iris);
    ellipsoid(eye, [-0.022, 0.044, 0.052], [0.021, 0.019, 0.008], glint);
    ellipsoid(eye, [0.026, -0.02, 0.055], [0.009, 0.008, 0.004], glint);
    stroke(head, [[0, 0.261, 0.419], [sign * 0.015, 0.233, 0.416], [sign * 0.051, 0.226, 0.409], [sign * 0.082, 0.246, 0.406]], 0.0065, ink);
    for (let i = 0; i < 2; i++) {
      stroke(head, [[sign * 0.301, 0.238 - 0.028 * i, 0.327], [sign * 0.44, 0.248 - 0.035 * i, 0.30], [sign * (0.543 - 0.015 * i), 0.261 - 0.053 * i, 0.262]], 0.0036, whisker);
    }
  }
  ellipsoid(head, [0, 0.280, 0.415], [0.047, 0.029, 0.024], blush);

  const tailPath = new THREE.CatmullRomCurve3([
    [0.18, 0.20, -0.20], [0.37, 0.15, -0.28], [0.53, 0.22, -0.26],
    [0.626, 0.368, -0.185], [0.642, 0.510, -0.144], [0.563, 0.585, -0.116]
  ].map(p => new THREE.Vector3(...p)));
  const segments = 42, sides = 20;
  const tailGeo = new THREE.TubeGeometry(tailPath, segments, 1, sides, false);
  const tailPosition = tailGeo.attributes.position, tailColor = [];
  const baseColor = new THREE.Color(0xf1aa62), stripeColor = new THREE.Color(0xcc813f), tipColor = new THREE.Color(0xffeccd);
  const sample = new THREE.Color(), p = new THREE.Vector3();
  for (let j = 0; j <= segments; j++) {
    const t = j / segments;
    const center = tailPath.getPointAt(t);
    const tip = Math.min(1, (1 - t) / 0.15);
    const radius = (0.092 - 0.012 * t) * Math.sqrt(1 - (1 - tip) ** 2);
    const band = 1 - smooth(0.044, 0.058, Math.min(Math.abs(t - 0.25), Math.abs(t - 0.50), Math.abs(t - 0.73)));
    sample.copy(baseColor).lerp(stripeColor, band).lerp(tipColor, smooth(0.815, 0.837, t));
    for (let i = 0; i <= sides; i++) {
      const idx = j * (sides + 1) + i;
      p.fromBufferAttribute(tailPosition, idx).sub(center).multiplyScalar(radius).add(center);
      tailPosition.setXYZ(idx, p.x, p.y, p.z);
      tailColor.push(sample.r, sample.g, sample.b);
    }
  }
  tailGeo.setAttribute('color', new THREE.Float32BufferAttribute(tailColor, 3));
  tailGeo.computeVertexNormals();
  const tail = new THREE.Mesh(tailGeo, matte(0xffffff, { vertexColors: true }));
  tail.castShadow = true;
  group.add(tail);
  return { group, head };
}

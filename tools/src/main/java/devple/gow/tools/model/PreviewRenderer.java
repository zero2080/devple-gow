package devple.gow.tools.model;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

/**
 * 검증용 초간단 소프트웨어 래스터라이저. 정사영 + Z 버퍼 + 면 법선 램버트 셰이딩 (양면).
 * 정면(-Z 방향에서), 측면(+X 에서), 3/4 위쪽 시점 3장을 가로로 붙여 PNG 로 저장한다.
 */
public final class PreviewRenderer {

	private PreviewRenderer() {
	}

	public static void render(TriMesh m, Path png, int size) throws IOException {
		float[] bb = m.bounds();
		float cx = (bb[0] + bb[3]) / 2, cy = (bb[1] + bb[4]) / 2, cz = (bb[2] + bb[5]) / 2;
		float ext = Math.max(bb[3] - bb[0], Math.max(bb[4] - bb[1], bb[5] - bb[2]));
		if (!(ext > 0)) {
			ext = 1;
		}
		double[][] views = {{0, 0}, {Math.PI / 2, 0}, {Math.PI / 4, Math.toRadians(-30)}};
		BufferedImage img = new BufferedImage(size * views.length, size, BufferedImage.TYPE_INT_RGB);
		for (int v = 0; v < views.length; v++) {
			draw(m, img, v * size, size, cx, cy, cz, ext * 1.05f, views[v][0], views[v][1]);
		}
		ImageIO.write(img, "png", png.toFile());
	}

	private static void draw(TriMesh m, BufferedImage img, int ox, int size, float cx, float cy, float cz, float ext,
							 double yaw, double pitch) {
		float[] zb = new float[size * size];
		java.util.Arrays.fill(zb, Float.POSITIVE_INFINITY);
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				img.setRGB(ox + x, y, 0x202830);
			}
		}
		double cyw = Math.cos(yaw), syw = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
		float s = size / ext;
		int si = 0;
		for (TriMesh.Sub sub : m.subs) {
			int hue = palette(si++);
			int n = sub.pos.size();
			float[] sx = new float[n], sy = new float[n], sz = new float[n];
			for (int i = 0; i < n; i++) {
				float[] p = sub.pos.get(i);
				double x = p[0] - cx, y = p[1] - cy, z = p[2] - cz;
				double x1 = x * cyw - z * syw;
				double z1 = x * syw + z * cyw;
				double y2 = y * cp - z1 * sp;
				double z2 = y * sp + z1 * cp;
				sx[i] = (float) (x1 * s + size / 2.0);
				sy[i] = (float) (size / 2.0 - y2 * s);
				sz[i] = (float) z2;
			}
			for (int[] t : sub.tris) {
				int a = t[0], b = t[1], c = t[2];
				double ux = sx[b] - sx[a], uy = sy[b] - sy[a], uz = sz[b] - sz[a];
				double vx = sx[c] - sx[a], vy = sy[c] - sy[a], vz = sz[c] - sz[a];
				double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
				double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
				double lum = len == 0 ? 0.5 : 0.25 + 0.75 * Math.abs((nx * 0.3 - ny * 0.5 - nz * 0.8) / (len * 1.0)) / 1.0;
				lum = Math.min(1, lum);
				int col = shade(hue, lum);
				fill(img, zb, ox, size, sx[a], sy[a], sz[a], sx[b], sy[b], sz[b], sx[c], sy[c], sz[c], col);
			}
		}
	}

	private static void fill(BufferedImage img, float[] zb, int ox, int size, float x0, float y0, float z0, float x1,
							 float y1, float z1, float x2, float y2, float z2, int col) {
		int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
		int maxX = Math.min(size - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
		int minY = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
		int maxY = Math.min(size - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
		float area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
		if (area == 0 || minX > maxX || minY > maxY) {
			return;
		}
		for (int y = minY; y <= maxY; y++) {
			for (int x = minX; x <= maxX; x++) {
				float px = x + 0.5f, py = y + 0.5f;
				float w0 = ((x1 - px) * (y2 - py) - (x2 - px) * (y1 - py)) / area;
				float w1 = ((x2 - px) * (y0 - py) - (x0 - px) * (y2 - py)) / area;
				float w2 = 1 - w0 - w1;
				if (w0 < 0 || w1 < 0 || w2 < 0) {
					continue;
				}
				float z = w0 * z0 + w1 * z1 + w2 * z2;
				int k = y * size + x;
				if (z < zb[k]) {
					zb[k] = z;
					img.setRGB(ox + x, y, col);
				}
			}
		}
	}

	private static int palette(int i) {
		int[] p = {0xE0B080, 0x80C0E0, 0xA0E080, 0xE080A0, 0xC0A0E0, 0xE0E080, 0x80E0C0, 0xF0A060};
		return p[i % p.length];
	}

	private static int shade(int rgb, double l) {
		int r = (int) (((rgb >> 16) & 0xFF) * l), g = (int) (((rgb >> 8) & 0xFF) * l), b = (int) ((rgb & 0xFF) * l);
		return (r << 16) | (g << 8) | b;
	}
}

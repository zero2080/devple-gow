package devple.gow.engine.level;

import devple.gow.tools.anim.Mat4;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.anim.SkinnedModel;
import devple.gow.tools.model.GowModel;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 부착 관절 파트의 공간 (SkinnedModel.Part.jointLocal(Skeleton), 43 문서 §16): 부착 관절에 inverse bind 가 없으면 파트 정점은 부착 관절 공간의 바인드 메시이고
 * inverse bind 가 그 공간 기준 (invBind_j · bindWorld_j = bindWorld[부착]) → 모델 공간 파트처럼 skin. 부착 관절에 inverse bind 가 있으면 관절 로컬.
 */
class SkinAttachRuleTest {

	private static WadFile wad(String name) throws Exception {
		Path p = Path.of(System.getProperty("gow.rom", "../gow-rom"), name + ".WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return WadFile.load(p);
	}

	private static Skeleton skeletonOf(WadFile w, String mdl) {
		for (Skeleton s : Skeleton.loadAll(w)) {
			if (Skeleton.groupRefs(w, s.name).contains(mdl)) {
				return s;
			}
		}
		throw new AssertionError(mdl);
	}

	private static SkinnedModel model(WadFile w, String mdl) {
		return new SkinnedModel(GowModel.load(w).stream().filter(m -> m.name.equals(mdl)).findFirst().orElseThrow(), 0, 0);
	}

	/** 도시 위 거대한 아레스 (ATHN03A goaresanimgroup1): 몸 파트 7 개가 group9 (배율 노드, inverse bind 없음) 에 붙고 정점은 그 공간 — 예전 규칙은 몸을 찢었다. */
	@Test
	void aresBodyIsSkinnedFromTheAttachJointSpace() throws Exception {
		WadFile w = wad("ATHN03A");
		Skeleton sk = skeletonOf(w, "MDL_AresAnimGroup1");
		float[][] bw = sk.bindWorld();
		int body = 0;
		for (SkinnedModel.Part p : model(w, "MDL_AresAnimGroup1").parts) {
			if (p.attachJoint == 0) {
				continue;
			}
			assertFalse(sk.joints.get(p.attachJoint).hasInverseBind(), p.name);
			assertTrue(p.jointLocal(), "예전 규칙: 부착 ≠ 0 → 관절 로컬");
			assertFalse(p.jointLocal(sk), "새 규칙: inverse bind 없는 부착 → skin");
			for (int[] jj : p.joints) {
				for (int j : jj) {
					if (j < sk.size() && sk.joints.get(j).hasInverseBind()) {
						float[] s = Mat4.mul(sk.inverseBind[sk.joints.get(j).invIndex()], bw[j]);
						assertTrue(Mat4.diff(s, bw[p.attachJoint]) < 0.01f, p.name + " 관절 " + j + ": invBind·bindWorld = bindWorld[부착]");
					}
				}
			}
			body++;
		}
		assertEquals(7, body, "몸 파트 (group9 부착)");
	}

	/** 메두사 전사 ME3 (ATHN02D): 부착 관절 MEW01JOCSTB1 에 inverse bind 가 있으면 그대로 관절 로컬. */
	@Test
	void attachJointWithInverseBindStaysJointLocal() throws Exception {
		WadFile w = wad("ATHN02D");
		Skeleton sk = skeletonOf(w, "MDL_ME3");
		int n = 0;
		for (SkinnedModel.Part p : model(w, "MDL_ME3").parts) {
			if (p.attachJoint != 0 && sk.joints.get(p.attachJoint).hasInverseBind()) {
				assertTrue(p.jointLocal(sk), p.name);
				n++;
			}
		}
		assertTrue(n >= 2, "STB 파트 " + n);
	}
}

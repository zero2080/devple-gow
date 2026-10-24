package devple.gow.engine.asset;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 블레이드 무기 WAD R_WPN&lt;코스튬&gt;_&lt;레벨&gt; 36 개 모두에서 블레이드 오브젝트·모델을 찾는다 (43 문서 §17). 기본 코스튬의 레벨 1–5 는 모델 이름이
 * {@code MDL_MAIblade} (소문자 b) 라 정확한 이름으로만 찾던 예전 엔진은 업그레이드 뒤 다음 레벨부터 블레이드를 그리지 못했다.
 */
class BladeWeaponWadTest {

	@Test
	void everyWeaponWadHasTheBladeModel() throws Exception {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(rom.resolve("R_WPN0_0.WAD")), "gow-rom 없음");
		List<String> lower = new ArrayList<>();
		int n = 0;
		for (int costume = 0; costume <= 5; costume++) {
			for (int level = 0; level <= 5; level++) {
				Path p = rom.resolve("R_WPN" + costume + "_" + level + ".WAD");
				if (!Files.exists(p)) {
					continue;
				}
				try (WadAssets a = new WadAssets(p)) {
					assertNotNull(BladeModels.bladeSkeleton(a), p.getFileName() + " maiblade");
					var m = BladeModels.bladeModel(a);
					assertNotNull(m, p.getFileName() + " MDL_MAIBlade (대소문자 무시)");
					if (a.gowModel("MDL_MAIBlade") == null) {
						lower.add(p.getFileName().toString());
					}
					n++;
				}
			}
		}
		assertEquals(36, n);
		assertEquals(List.of("R_WPN0_1.WAD", "R_WPN0_2.WAD", "R_WPN0_3.WAD", "R_WPN0_4.WAD", "R_WPN0_5.WAD",
				"R_WPN5_1.WAD", "R_WPN5_2.WAD", "R_WPN5_3.WAD", "R_WPN5_4.WAD", "R_WPN5_5.WAD"), lower, "MDL_MAIblade (소문자 b) 인 WAD");
	}
}

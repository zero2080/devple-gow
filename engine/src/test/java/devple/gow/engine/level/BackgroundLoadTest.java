package devple.gow.engine.level;

import devple.gow.engine.asset.Loader;
import devple.gow.engine.asset.WadAssets;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 백그라운드 적재 (48 문서 §8): CPU 단계는 GL 없이 돌고, 어느 스레드에서 돌려도 결과가 같다. */
class BackgroundLoadTest {

	private static Path rom() {
		Path p = Path.of(System.getProperty("gow.rom", "../gow-rom")).toAbsolutePath();
		for (Path d = p.getParent(); !Files.exists(p.resolve("R_PERMK.WAD")) && d != null; d = d.getParent()) {
			p = d.resolve("gow-rom");
		}
		return p;
	}

	@Test
	void syncModeRunsOnCallerAndErrorsPropagate() {
		String old = System.getProperty("gow.syncLoad");
		System.setProperty("gow.syncLoad", "true");
		try {
			var f = Loader.submit("t", () -> Thread.currentThread().getName());
			assertTrue(f.isDone());
			assertEquals(Thread.currentThread().getName(), Loader.join(f, "t"));
		} finally {
			if (old == null) {
				System.clearProperty("gow.syncLoad");
			} else {
				System.setProperty("gow.syncLoad", old);
			}
		}
		var bad = Loader.submit("bad", () -> {
			throw new IOException("없음");
		});
		assertThrows(UncheckedIOException.class, () -> Loader.join(bad, "bad"));
		var name = Loader.submit("name", () -> Thread.currentThread().getName());
		assertNotEquals(Thread.currentThread().getName(), Loader.join(name, "name")); // 비동기 = 적재 스레드
	}

	@Test
	void levelPrepareOnLoaderThreadMatchesCallerThread() throws Exception {
		Path wad = rom().resolve("DEST00.WAD");
		Assumptions.assumeTrue(Files.exists(wad), "gow-rom 없음");
		long here = new LevelScene(wad, -2, false).prepare().preparedHash();
		var f = Loader.submit("DEST00", () -> new LevelScene(wad, -2, false).prepare());
		LevelScene bg = Loader.join(f, "DEST00");
		assertEquals(here, bg.preparedHash());
		// 미리 해석한 WadAssets = 바로 해석한 것
		Path permk = rom().resolve("R_PERMK.WAD");
		WadAssets.prefetch(permk);
		WadAssets pre = new WadAssets(permk);
		WadAssets direct = new WadAssets(permk);
		assertEquals(direct.modelNames(), pre.modelNames());
		assertEquals(direct.wad().tags().size(), pre.wad().tags().size());
		WadAssets.dropPrefetched();
	}
}

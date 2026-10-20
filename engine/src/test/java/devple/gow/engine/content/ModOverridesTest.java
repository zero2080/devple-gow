package devple.gow.engine.content;

import devple.gow.engine.anim.Animator;
import devple.gow.engine.asset.WadAssets;
import devple.gow.engine.audio.NullAudioBackend;
import devple.gow.engine.audio.SoundManager;
import devple.gow.engine.game.Tweaks;
import devple.gow.tools.anim.Animation;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.audio.PcmSound;
import devple.gow.tools.audio.WavClip;
import devple.gow.tools.content.Content;
import devple.gow.tools.content.GameConfig;
import devple.gow.tools.content.TextureOverrides;
import devple.gow.tools.texture.TextureDecoder;
import devple.gow.tools.texture.TextureLibrary;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** 종류별 교체: 텍스처 PNG (§14)·애니메이션 교체/속도 (§12)·사운드 WAV (§11) — 49 문서. */
class ModOverridesTest {

	@TempDir
	Path tmp;

	private final Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	@AfterEach
	void reset() {
		Content.install(null);
	}

	private Path mod() throws IOException {
		Path m = Files.createDirectories(tmp.resolve("m"));
		Content.install(Content.of(new GameConfig(new Properties()), List.of(m)));
		return m;
	}

	private void noMods() {
		Content.install(Content.of(new GameConfig(new Properties()), List.of()));
	}

	private void needRom(String wad) {
		Assumptions.assumeTrue(Files.exists(rom.resolve(wad)), "gow-rom 없음");
	}

	// ---- §14 텍스처 ----

	@Test
	void fitKeepsUvSpace() {
		BufferedImage same = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
		assertEquals(64, TextureOverrides.fit(same, 64, 32).getWidth());
		// 2 배 PNG → 2 배 텍스처, 그대로 복사
		BufferedImage big = new BufferedImage(128, 64, BufferedImage.TYPE_INT_ARGB);
		big.setRGB(127, 63, 0x40112233);
		BufferedImage f = TextureOverrides.fit(big, 64, 32);
		assertEquals(128, f.getWidth());
		assertEquals(64, f.getHeight());
		assertEquals(0x40112233, f.getRGB(127, 63));
		// 2 의 거듭제곱이 아닌 원본 512×72 (업로드 512×128): 1024×144 PNG → 1024×256, 위 144 줄에 그림
		BufferedImage np = new BufferedImage(1024, 144, BufferedImage.TYPE_INT_ARGB);
		np.setRGB(5, 143, 0xFF00FF00);
		f = TextureOverrides.fit(np, 512, 72);
		assertEquals(1024, f.getWidth());
		assertEquals(256, f.getHeight());
		assertEquals(0xFF00FF00, f.getRGB(5, 143));
		assertEquals(0, f.getRGB(5, 200));
		// 정수배가 아니면 같은 비율 자리로 크기 변경: 100×50 → 64×32 원본 (배율 ~1.56) → 128×64 텍스처의 128×64 자리
		f = TextureOverrides.fit(new BufferedImage(100, 50, BufferedImage.TYPE_INT_RGB), 64, 32);
		assertEquals(128, f.getWidth());
		assertEquals(0xFF000000, f.getRGB(127, 63)); // 알파 없는 PNG = 불투명
	}

	@Test
	void pngOverrideReplacesDecodedTexture() throws Exception {
		needRom("R_HERO0.WAD");
		Path wadPath = rom.resolve("R_HERO0.WAD");
		TextureLibrary lib = new TextureLibrary(WadFile.load(wadPath));
		List<TextureLibrary.Entry> es = lib.textures().stream().filter(e -> e.gfx().frames() <= 1).toList();
		TextureLibrary.Entry a = es.getFirst(), b = es.stream().filter(e -> !e.txr().name().equals(a.txr().name())).findFirst().orElseThrow();
		int w = a.gfx().width(), h = a.gfx().height();

		noMods();
		WadAssets plain = new WadAssets(wadPath);
		int[] dec = TextureDecoder.decode(a.gfx(), a.pal(), 0);
		assertEquals(dec[0], plain.textureImage(a.txr().name()).getRGB(0, 0), "모드 없음 = 해독 그대로");

		Path m = mod();
		BufferedImage png = new BufferedImage(w * 2, h * 2, BufferedImage.TYPE_INT_ARGB);
		png.setRGB(0, 0, 0x40C08020); // 반투명: 8 비트 알파 그대로 (PS2 0x80 아님)
		Files.createDirectories(m.resolve("textures/R_HERO0"));
		ImageIO.write(png, "png", m.resolve("textures/R_HERO0/" + a.txr().name() + ".png").toFile());
		BufferedImage glob = new BufferedImage(b.gfx().width(), b.gfx().height(), BufferedImage.TYPE_INT_ARGB);
		glob.setRGB(0, 0, 0xFF0000FF);
		ImageIO.write(glob, "png", m.resolve("textures/" + b.txr().name() + ".png").toFile());
		// 다른 WAD 디렉터리의 같은 이름은 영향 없음
		Files.createDirectories(m.resolve("textures/OTHER"));
		ImageIO.write(glob, "png", m.resolve("textures/OTHER/" + a.txr().name() + ".png").toFile());

		WadAssets wa = new WadAssets(wadPath);
		BufferedImage got = wa.textureImage(a.txr().name());
		int padW = Integer.bitCount(w) == 1 ? w : Integer.highestOneBit(w) << 1;
		assertEquals(padW * 2, got.getWidth(), "2 배 PNG → 2 배 업로드 크기");
		assertEquals(0x40C08020, got.getRGB(0, 0));
		assertEquals(0xFF0000FF, wa.textureImage(b.txr().name()).getRGB(0, 0), "전역 textures/");
		assertNull(TextureOverrides.find("R_HERO0.WAD", "TXR_없음", -1));
	}

	// ---- §12 애니메이션 ----

	@Test
	void animReplaceAndSpeed() throws Exception {
		needRom("R_HERO0.WAD");
		WadFile hero = WadFile.load(rom.resolve("R_HERO0.WAD"));
		AnimationSet set = AnimationSet.load(hero, "ANM_hero");
		Animation idle = set.findOriginal("standIdle"), walk = set.findOriginal("walkBlend1");
		assertNotNull(idle);
		assertNotNull(walk);

		noMods();
		assertSame(idle, set.find("standIdle"));

		Path m = mod();
		Files.writeString(m.resolve("anim.properties"), """
				replace.standidle=walkBlend1
				replace.walkBlend2=없는클립
				speed.walkBlend1=2
				speed.land@ANM_hero=0.5
				speed.land@ANM_other=3
				""");
		assertSame(walk, set.find("standIdle"), "대소문자 무시");
		assertSame(set.findOriginal("walkBlend2"), set.find("walkBlend2"), "세트에 없는 대상은 무시");

		Tweaks tw = new Tweaks();
		Skeleton sk = Skeleton.loadAll(hero).stream().filter(s -> s.name.equals("hero")).findFirst().orElseThrow();
		Animator an = new Animator(sk, set, tw, "/Animation/goHero/");
		an.play("standIdle", true);
		assertSame(walk, an.current());
		an.update(0.1f);
		assertEquals(0.2f, an.time(), 1e-5f);
		an.play("land", true);
		an.update(0.1f);
		assertEquals(0.05f, an.time(), 1e-5f, "세트 지정 키");
		an.playClip(walk, 0, 1.5f); // 전투 기술 속도 × 모드 배율
		an.update(0.1f);
		assertEquals(0.3f, an.time(), 1e-5f);
	}

	// ---- §11 사운드 ----

	private static byte[] wav(int rate, int ch, short[] s, int loopStart) throws IOException {
		byte[] base = new PcmSound("t", rate, ch, s).toWav();
		if (loopStart < 0) {
			return base;
		}
		ByteBuffer smpl = ByteBuffer.allocate(8 + 36 + 24).order(ByteOrder.LITTLE_ENDIAN);
		smpl.put("smpl".getBytes()).putInt(36 + 24);
		smpl.position(8 + 28);
		smpl.putInt(1).putInt(0).putInt(0).putInt(0).putInt(loopStart).putInt(s.length / ch - 1);
		byte[] out = new byte[base.length + smpl.capacity()];
		System.arraycopy(base, 0, out, 0, base.length);
		System.arraycopy(smpl.array(), 0, out, base.length, smpl.capacity());
		ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).putInt(4, out.length - 8);
		return out;
	}

	@Test
	void wavParse() throws IOException {
		short[] st = {1, -1, 2, -2, 3, -3, 4, -4};
		WavClip w = WavClip.read("x", wav(44100, 2, st, 1));
		assertEquals(2, w.channels());
		assertEquals(4, w.frames());
		assertEquals(1, w.loopStart());
		assertArrayEquals(st, w.pcm().samples());
		assertEquals(0, w.mono().pcm()[0]);
		assertTrue(w.mono().looped());
		assertEquals(-1, WavClip.read("y", wav(8000, 1, new short[]{5, 6}, -1)).loopStart());
		assertThrows(IllegalArgumentException.class, () -> WavClip.read("z", new byte[]{1, 2, 3}));
	}

	@Test
	void wavReplacesNamedSoundAndMusic() throws Exception {
		needRom("ATHN01A.WAD");
		Path m = mod();
		Files.createDirectories(m.resolve("sound"));
		Files.write(m.resolve("sound/snd_ig_popup.wav"), wav(22050, 1, new short[2205], -1));
		Files.write(m.resolve("sound/SND_M_ATH1C1_4.wav"), wav(32000, 2, new short[6400], -1));
		SoundManager sm = SoundManager.headless(rom);
		sm.load(WadFile.load(Content.file(rom, "R_PERMK.WAD")));
		sm.load(WadFile.load(Content.file(rom, "ATHN01A.WAD")));
		NullAudioBackend b = (NullAudioBackend) sm.backend();

		int n = b.log().size();
		int h = sm.play("SND_IG_POPUP", new Vector3f(1, 0, 0));
		assertTrue(h > 0);
		assertEquals(n + 1, b.log().size(), "WAV 보이스 하나 (그레인 실행 없음)");
		NullAudioBackend.Started s = b.log().getLast();
		assertEquals("clip", s.kind());
		assertEquals("wav:snd_ig_popup.wav", s.name());
		assertEquals(22050, s.rate());
		assertFalse(s.loop());
		assertNotNull(s.voice().pos, "3D 위치 그대로");
		assertTrue(s.voice().gain > 0);
		assertTrue(sm.playing(h));
		sm.update(0.2f);
		assertFalse(sm.playing(h), "0.1 초 WAV 끝");

		// 다른 소리는 원래 경로
		n = b.log().size();
		assertTrue(sm.play("SND_PICKUPS_ORBS_E") > 0);
		sm.update(0.05f);
		assertTrue(b.log().subList(n, b.log().size()).stream().noneMatch(x -> x.name().startsWith("wav:")));

		// 음악: 스테레오 → 메모리 스트림, 기본 반복
		h = sm.music("SND_M_ATH1C1_4", 0);
		assertTrue(h > 0);
		s = b.log().getLast();
		assertEquals("stream", s.kind());
		assertEquals("wav:SND_M_ATH1C1_4.wav", s.name());
		sm.update(0.5f);
		assertTrue(sm.playing(h), "반복");
		assertTrue(b.streamed(s.id()) > 6400, "루프로 길이보다 많이 읽음");
		sm.stopMusic(0);
		assertFalse(b.playing(s.id()));
	}
}

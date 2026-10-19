package devple.gow.engine.video;

import devple.gow.tools.pak.GowDisc;
import devple.gow.tools.video.MovieSource;
import devple.gow.tools.video.Mpeg2Video;
import devple.gow.tools.video.PssDemuxer;
import devple.gow.tools.video.SsAudio;
import devple.gow.tools.video.VideoFrame;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 컷신 (18·33 문서): PSS 디먹스, SShd 오디오, JCodec MPEG-2, 재생기 시계. ISO 가 있어야 실행된다. */
class MovieTest {

	private static Path rom() {
		return Path.of(System.getProperty("gow.rom", "../gow-rom"));
	}

	private static MovieSource open(String name) throws Exception {
		Path iso = MoviePlayer.isoPath(rom());
		Assumptions.assumeTrue(iso != null && Files.exists(iso), "ISO 없음");
		return MovieSource.open(name, rom(), iso, false);
	}

	@Test
	void a01mp05DemuxDecode() throws Exception {
		try (MovieSource s = open("a01mp05")) {
			assertEquals("A01MP05.PSS", s.name());
			PssDemuxer d = new PssDemuxer(s);
			SsAudio audio = new SsAudio();
			List<VideoFrame> frames = new ArrayList<>();
			Mpeg2Video v = new Mpeg2Video(frames::add);
			long samples = 0;
			PssDemuxer.Packet p;
			while ((p = d.next()) != null) {
				if (p.stream == PssDemuxer.VIDEO) {
					v.feed(p.data, 0, p.len);
				} else {
					assertEquals(0xFF, p.sub);
					audio.feed(p.data, 0, p.len);
					samples += audio.drain(new short[audio.available()], 0, audio.available());
				}
			}
			v.flush();
			assertEquals(640, v.width());
			assertEquals(480, v.height());
			assertEquals(2, v.aspectCode());
			assertEquals(29.97, v.fps(), 0.01);
			assertEquals(246, frames.size());
			assertEquals(0, v.errors());
			for (int i = 0; i < frames.size(); i++) {
				assertEquals(i, frames.get(i).index());
			}
			assertEquals(SsAudio.CODEC_PCM16, audio.codec);
			assertEquals(48000, audio.rate);
			assertEquals(2, audio.channels);
			assertEquals(0x200, audio.interleave);
			assertEquals(1653760, audio.bodySize);
			assertEquals(audio.bodySize / 2, samples);
			// 이 파일은 오디오(8.61초)가 비디오(8.21초)보다 0.4초 길다 — 재생기는 마지막 프레임을 유지한 채 오디오를 끝까지 튼다
			assertEquals(8.2082, frames.size() / v.fps(), 1e-3);
			assertEquals(8.6133, samples / 2 / 48000.0, 1e-3);
		}
	}

	@Test
	void playerHeadlessClock() throws Exception {
		MovieSource s = open("A01MP05");
		try (MoviePlayer mp = new MoviePlayer(s, false, 5.0, 1f, true)) {
			int distinct = 0;
			VideoFrame last = null;
			for (int tick = 0; tick < 60 * 10 && !mp.finished(); tick++) {
				mp.update(1 / 60f);
				if (mp.frame() != last && mp.frame() != null) {
					last = mp.frame();
					distinct++;
					assertTrue(last.time() >= 5.0 - 1e-6);
				}
				Thread.sleep(1);
			}
			assertTrue(mp.finished());
			assertEquals(245, last.index());
			assertTrue(distinct > 90, "표시 프레임 " + distinct);
		}
	}

	/** 레벨 스크립트의 PlayMovie 이름 46개 + 실행 파일 표 이름은 모두 TOC 파일로 바로 대응한다. */
	@Test
	void scriptNamesResolve() throws Exception {
		Path iso = MoviePlayer.isoPath(rom());
		Assumptions.assumeTrue(iso != null && Files.exists(iso), "ISO 없음");
		try (GowDisc disc = new GowDisc(iso)) {
			Set<String> toc = disc.toc().unique().keySet();
			for (String n : List.of("a01mp05", "a01MP02", "a01Mp03", "Semo02", "MA360", "MAtBolt", "mewintro", "SKDIntro",
					"a06Mp05b", "A07MP04", "Bonus19", "Bonus01", "Bonus20", "Bonus24", "Semo13", "p00MP02", "MagicBZK",
					"ballway", "h01Mp03", "Semo01", "SatIntro", "Bonus21", "Bonus25", "p03mp06", "p04mp05")) {
				String base = devple.gow.tools.video.MovieNames.fileBase(n);
				assertTrue(toc.contains(base + ".PSS"), n);
			}
			assertTrue(toc.contains("SEMO01.PSW"));
			assertFalse(toc.contains("BONUS01.PSW"));   // 보너스 영상은 4:3 판만
		}
	}
}

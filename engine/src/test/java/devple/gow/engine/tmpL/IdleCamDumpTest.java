package devple.gow.engine.tmpL;

import devple.gow.tools.anim.*;
import devple.gow.tools.level.*;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.Arrays;

class IdleCamDumpTest {
	@Test
	void dump() throws Exception {
		LevelData d = new LevelData(WadFile.load(Path.of("../gow-rom/ATHN01A.WAD")));
		for (LevelLayout.Placement p : d.layout.placements()) {
			String n = p.instance().name().toLowerCase();
			if (!n.contains("idlecam")) continue;
			System.out.println("== " + p.instance().name() + " refs " + p.object().refs());
			System.out.println(" inst " + Arrays.toString(p.instance().matrix()));
			Skeleton sk = p.object().skeleton();
			for (Skeleton.Joint j : sk.joints) {
				System.out.printf(" joint %d %s fl 0x%x parent %d idleRot %s idlePos %s%n", j.index(), j.name(), j.flags(), j.parent(),
						Arrays.toString(sk.idleRot[j.index()]), Arrays.toString(sk.idlePos[j.index()]));
				System.out.println("   bindLocal " + Arrays.toString(sk.bindLocal[j.index()]));
			}
			String anm = null;
			for (String r : p.object().refs()) if (r.startsWith("ANM_")) anm = r;
			Animation a = AnimationSet.load(d.wad, anm).all().getFirst();
			System.out.printf(" anim %s dur %f ft %f frames %d flags 0x%x speed %f%n", a.name, a.duration, a.frameTime, a.frameCount(), a.flags, a.speed);
			for (AnimTrack t : a.rotTracks) System.out.printf("  rot track base %d add %b start %d count %d streams %s first %s last %s%n", t.baseStream(), t.additive(), t.start(), t.count(), Arrays.toString(t.streams()),
					Arrays.toString(Arrays.stream(t.values()).mapToDouble(v->v[0]).toArray()), Arrays.toString(Arrays.stream(t.values()).mapToDouble(v->v[v.length-1]).toArray()));
			for (Animation.StateDescr sd : a.descrs) System.out.println("  descr " + sd); System.out.println("  acts " + AnimationSet.load(d.wad, anm).all().size());
			for (AnimTrack t : a.posTracks) System.out.println("  pos track " + Arrays.toString(t.streams()));
			for (AnimTrack t : a.objectTracks) System.out.println("  obj track " + Arrays.toString(t.streams()));
			for (AnimTrack t : a.channelTracks) System.out.println("  chan track " + Arrays.toString(t.streams()));
			Animation.Baked bk = a.bake(sk);
			for (int f = 0; f < bk.frames(); f += 15) {
				System.out.printf("  f%d rot %s%n", f, Arrays.toString(Arrays.copyOf(bk.rot()[f], sk.size()*4)));
			}
		}
		for (LevelData.PlacedCamera c : d.placedCameras) {
			if (!c.def().name().toLowerCase().contains("idle")) continue;
			System.out.println("CAM " + c.def().name() + " owner " + c.owner() + " local " + Arrays.toString(c.def().local()) + " world " + Arrays.toString(c.world()));
			System.out.printf("  flags 0x%x fov %f look %s%n", c.def().p().flags(), c.def().p().fovRad(), Arrays.toString(c.def().p().lookOffset()));
		}
	}
}

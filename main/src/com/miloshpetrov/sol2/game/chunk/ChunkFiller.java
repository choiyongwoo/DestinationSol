package com.miloshpetrov.sol2.game.chunk;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.miloshpetrov.sol2.*;
import com.miloshpetrov.sol2.common.Col;
import com.miloshpetrov.sol2.common.SolMath;
import com.miloshpetrov.sol2.game.*;
import com.miloshpetrov.sol2.game.asteroid.Asteroid;
import com.miloshpetrov.sol2.game.asteroid.AsteroidBuilder;
import com.miloshpetrov.sol2.game.dra.*;
import com.miloshpetrov.sol2.game.input.*;
import com.miloshpetrov.sol2.game.maze.Maze;
import com.miloshpetrov.sol2.game.planet.*;
import com.miloshpetrov.sol2.game.ship.*;

import java.util.ArrayList;

public class ChunkFiller {
  public static final float DUST_DENSITY = .2f;
  public static final float ASTEROID_DENSITY = .004f;

  public static final float JUNK_MAX_SZ = .3f;
  public static final float JUNK_MAX_ROT_SPD = 45f;
  public static final float JUNK_MAX_SPD_LEN = .3f;

  public static final float FAR_JUNK_MAX_SZ = 2f;
  public static final float FAR_JUNK_MAX_ROT_SPD = 10f;

  public static final float ENEMY_MAX_SPD = .3f;
  public static final float ENEMY_MAX_ROT_SPD = 1f;
  public static final float DUST_SZ = .02f;
  private final SpaceObjConfig mySysConfig;
  private final SpaceObjConfig myMazeConfig;
  private final SpaceObjConfig myBeltConfig;

  public ChunkFiller(HullConfigs hullConfigs, TexMan texMan) {
    JsonReader r = new JsonReader();
    FileHandle configFile = SolFiles.readOnly(Const.CONFIGS_DIR + "spaceObjs.json");
    JsonValue parsed = r.parse(configFile);
    JsonValue sysJson = parsed.get("sys");
    mySysConfig = new SpaceObjConfig(sysJson, hullConfigs, texMan, configFile);
    JsonValue mazeJson = parsed.get("maze");
    myMazeConfig = new SpaceObjConfig(mazeJson, hullConfigs, texMan, configFile);
    JsonValue beltJson = parsed.get("asteroidBelt");
    myBeltConfig = new SpaceObjConfig(beltJson, hullConfigs, texMan, configFile);
  }


  public void fill(SolGame game, Vector2 chunk, RemoveController remover) {
    if (DebugAspects.NO_OBJS) return;

    Vector2 chCenter = new Vector2(chunk);
    chCenter.add(Const.CHUNK_SIZE / 2, Const.CHUNK_SIZE / 2);
    fillDust(game, chunk, chCenter, remover);

    SpaceObjConfig conf = getConfig(game, chCenter);

    fillFarJunk(game, chunk, chCenter, remover, DraLevel.FAR_BG_3, conf);
    fillFarJunk(game, chunk, chCenter, remover, DraLevel.FAR_BG_2, conf);
    fillFarJunk(game, chunk, chCenter, remover, DraLevel.FAR_BG_1, conf);
    fillJunk(game, chunk, remover, conf);

    if (conf == mySysConfig) {
      Vector2 startPos = game.getGalaxyFiller().getMainStation().getPos();
      float dst = chCenter.dst(startPos);
      if (dst > Const.CHUNK_SIZE) {
        fillAsteroids(game, chunk, remover);
        for (ShipConfig enemyConf : conf.enemies) {
          fillEnemies(game, chunk, remover, enemyConf);
        }
      }
    } // belt enemies & asteroids here
  }

  private SpaceObjConfig getConfig(SolGame game, Vector2 chCenter) {
    PlanetMan pm = game.getPlanetMan();
    SolSystem sys = pm.getNearestSystem(chCenter);
    SpaceObjConfig conf;
    if (sys.getPos().dst(chCenter) < sys.getRadius()) {
      // check star, belt;
      Planet p = pm.getNearestPlanet(chCenter);
      if (p.getPos().dst(chCenter) < p.getFullHeight() + Const.CHUNK_SIZE) {
        conf = null;
      } else {
        conf = mySysConfig;
      }
    } else {
      Maze m = pm.getNearestMaze(chCenter);
      if (m.getPos().dst(chCenter) < m.getRadius()) {
        conf = myMazeConfig;
      } else {
        conf = null;
      }
    }
    return conf;
  }

  private void fillEnemies(SolGame game, Vector2 chunk, RemoveController remover, ShipConfig enemyConf) {
    int count = getEntityCount(enemyConf.density);
    if (count == 0) return;
    for (int i = 0; i < count; i++) {
      Vector2 enemyPos = getRndPos(chunk);
      SolShip ship = buildSpaceEnemy(game, enemyPos, remover, enemyConf);
      if (ship != null) game.getObjMan().addObjDelayed(ship);
    }
  }

  public SolShip buildSpaceEnemy(SolGame game, Vector2 pos, RemoveController remover, ShipConfig enemyConf) {
    if (!game.isPlaceEmpty(pos)) return null;
    Vector2 spd = new Vector2();
    SolMath.fromAl(spd, SolMath.rnd(180), SolMath.rnd(0, ENEMY_MAX_SPD));
    float rotSpd = SolMath.rnd(ENEMY_MAX_ROT_SPD);
    float detectionDist = game.getCam().getSpaceViewDist();
    Pilot provider = new AiPilot(new NoDestProvider(), false, Fraction.EHAR, true, null, detectionDist);
    HullConfig config = enemyConf.hull;
    return game.getShipBuilder().buildNew(game, pos, spd, 0, rotSpd, provider, enemyConf.items, config, false, false,
      remover, false, 20f, null);
  }

  private void fillAsteroids(SolGame game, Vector2 chunk, RemoveController remover) {
    int count = getEntityCount(ASTEROID_DENSITY);
    if (count == 0) return;
    for (int i = 0; i < count; i++) {
      Vector2 asteroidPos = getRndPos(chunk);
      if (!game.isPlaceEmpty(asteroidPos)) continue;
      int modelNr = SolMath.intRnd(AsteroidBuilder.VARIANT_COUNT);
      Asteroid a = game.getAsteroidBuilder().build(game, asteroidPos, modelNr, remover);
      game.getObjMan().addObjDelayed(a);
    }
  }

  private void fillFarJunk(SolGame game, Vector2 chunk, Vector2 chCenter, RemoveController remover, DraLevel draLevel,
    SpaceObjConfig conf)
  {
    if (conf == null) return;
    int count = getEntityCount(conf.farJunkDensity);
    if (count == 0) return;

    ArrayList<Dra> dras = new ArrayList<Dra>();
    TexMan texMan = game.getTexMan();
    for (int i = 0; i < count; i++) {
      TextureAtlas.AtlasRegion tex = SolMath.elemRnd(conf.farJunkTexs);
      if (SolMath.test(.5f)) tex = texMan.getFlipped(tex);
      float sz = SolMath.rnd(.3f, 1) * FAR_JUNK_MAX_SZ;
      Vector2 junkPos = getRndPos(chunk);
      junkPos.sub(chCenter);
      RectSprite s = new RectSprite(tex, sz, 0, 0, junkPos, draLevel, SolMath.rnd(180), SolMath.rnd(FAR_JUNK_MAX_ROT_SPD), Col.G);
      dras.add(s);
    }
    DrasObj so = new DrasObj(dras, new Vector2(chCenter), new Vector2(), remover, false, true);
    game.getObjMan().addObjDelayed(so);
  }

  private void fillJunk(SolGame game, Vector2 chunk, RemoveController remover, SpaceObjConfig conf) {
    if (conf == null) return;
    int count = getEntityCount(conf.junkDensity);
    if (count == 0) return;

    for (int i = 0; i < count; i++) {
      Vector2 junkPos = getRndPos(chunk);

      TextureAtlas.AtlasRegion tex = SolMath.elemRnd(conf.junkTexs);
      if (SolMath.test(.5f)) tex = game.getTexMan().getFlipped(tex);
      float sz = SolMath.rnd(.3f, 1) * JUNK_MAX_SZ;
      float rotSpd = SolMath.rnd(JUNK_MAX_ROT_SPD);
      RectSprite s = new RectSprite(tex, sz, 0, 0, new Vector2(), DraLevel.JUNK, SolMath.rnd(180), rotSpd, Col.LG);
      ArrayList<Dra> dras = new ArrayList<Dra>();
      dras.add(s);

      Vector2 spd = new Vector2();
      SolMath.fromAl(spd, SolMath.rnd(180), SolMath.rnd(JUNK_MAX_SPD_LEN));
      DrasObj so = new DrasObj(dras, junkPos, spd, remover, false, true);
      game.getObjMan().addObjDelayed(so);
    }
  }

  private void fillDust(SolGame game, Vector2 chunk, Vector2 chCenter, RemoveController remover) {
    ArrayList<Dra> dras = new ArrayList<Dra>();
    int count = getEntityCount(DUST_DENSITY);
    if (count == 0) return;
    TextureAtlas.AtlasRegion tex = game.getTexMan().whiteTex;
    for (int i = 0; i < count; i++) {
      Vector2 dustPos = getRndPos(chunk);
      dustPos.sub(chCenter);
      RectSprite s = new RectSprite(tex, DUST_SZ, 0, 0, dustPos, DraLevel.JUNK, 0, 0, Col.W);
      dras.add(s);
    }
    DrasObj so = new DrasObj(dras, chCenter, new Vector2(), remover, false, true);
    game.getObjMan().addObjDelayed(so);
  }

  private Vector2 getRndPos(Vector2 chunk) {
    Vector2 pos = new Vector2(chunk);
    pos.x += SolMath.rnd(0, Const.CHUNK_SIZE);
    pos.y += SolMath.rnd(0, Const.CHUNK_SIZE);
    return pos;
  }

  private int getEntityCount(float density) {
    float amt = Const.CHUNK_SIZE * Const.CHUNK_SIZE * density;
    if (amt >= 1) return (int) amt;
    return SolMath.test(amt) ? 1 : 0;
  }

}

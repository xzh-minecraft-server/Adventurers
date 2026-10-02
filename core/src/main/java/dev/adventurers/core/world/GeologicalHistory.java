package dev.adventurers.core.world;

import dev.adventurers.core.api.Numbers;
import java.util.*;

/** Version 2 geography. A bounded, deterministic reconstruction, never a wall-clock or chunk-order simulation. */
public final class GeologicalHistory {
    public static final int EPOCHS = 16, DAYS_PER_EPOCH = 12;
    private static final int COLS = TerrainAtlas.COLS, ROWS = TerrainAtlas.ROWS, CELLS = COLS * ROWS;
    private GeologicalHistory() {}
    private record Vector(double x, double y, double z) {
        Vector add(Vector b) { return new Vector(x+b.x,y+b.y,z+b.z); }
        Vector scale(double v) { return new Vector(x*v,y*v,z*v); }
        double dot(Vector b) { return x*b.x+y*b.y+z*b.z; }
        Vector cross(Vector b) { return new Vector(y*b.z-z*b.y,z*b.x-x*b.z,x*b.y-y*b.x); }
        Vector unit() { return scale(1/Math.sqrt(dot(this))); }
        Vector rotate(Vector axis,double angle) {
            return scale(Math.cos(angle)).add(axis.cross(this).scale(Math.sin(angle)))
                    .add(axis.scale(axis.dot(this)*(1-Math.cos(angle))));
        }
    }
    private record Plate(Vector center, Vector axis, double speed, double buoyancy, double ore, double mana) {
        Plate move() { return new Plate(center.rotate(axis,speed),axis,speed,buoyancy,ore,mana); }
        Vector velocity(Vector point) { return axis.cross(point).scale(speed); }
    }
    private static Vector randomUnit(SplittableRandom random) {
        double y=random.nextDouble(-1,1),lon=random.nextDouble(-Math.PI,Math.PI),r=Math.sqrt(1-y*y);
        return new Vector(r*Math.cos(lon),y,r*Math.sin(lon));
    }
    static List<TerrainAtlas> generate(long seed,TerrainSettings settings) {
        var random=new SplittableRandom(seed);var plates=new ArrayList<Plate>();
        for(int i=0;i<12;i++)plates.add(new Plate(randomUnit(random),randomUnit(random),random.nextDouble(.02,.045),
                random.nextDouble(-1,1),random.nextDouble(.1,.7),random.nextDouble(.5,1.5)));
        var positions=new Vector[CELLS];
        for(int i=0;i<CELLS;i++) {
            double lat=-Math.PI/2+Math.PI*(i/COLS+.5)/ROWS,lon=-Math.PI+2*Math.PI*(i%COLS+.5)/COLS;
            positions[i]=new Vector(Math.cos(lat)*Math.cos(lon),Math.sin(lat),Math.cos(lat)*Math.sin(lon));
        }
        var history=new ArrayList<TerrainAtlas>();
        for(int epoch=0;epoch<=EPOCHS;epoch++) {
            if(epoch>0)plates=new ArrayList<>(plates.stream().map(Plate::move).toList());
            var previous=history.isEmpty()?null:history.getLast();
            double[] height=new double[CELLS],ore=new double[CELLS],mana=new double[CELLS];
            for(int i=0;i<CELLS;i++) {
                var p=positions[i];Plate a=null,b=null;double nearest=-2,second=-2;
                for(var plate:plates) {
                    double distance=plate.center.dot(p);
                    if(distance>nearest) { b=a;second=nearest;a=plate;nearest=distance; }
                    else if(distance>second) { b=plate;second=distance; }
                }
                double base=TerrainAtlas.SEA_LEVEL+a.buoyancy*108;
                var tangent=b.center.add(a.center.scale(-1));
                tangent=tangent.add(p.scale(-tangent.dot(p))).unit();
                double closing=a.velocity(p).add(b.velocity(p).scale(-1)).dot(tangent);
                double boundary=Math.exp(-(nearest-second)*24);
                double uplift=boundary*Math.max(0,closing)*145;
                if(previous==null)height[i]=base;
                else {
                    // Transport crust with its plate in 3D; retain the previous epoch's mountains and sediment.
                    var origin=p.rotate(a.axis,-a.speed);
                    double old=sample(previous,origin);
                    height[i]=Numbers.clamp(old+(base-old)*.08+uplift, TerrainAtlas.MIN_Y+12,240);
                }
                ore[i]=Numbers.unit(a.ore+uplift*.02);mana[i]=a.mana+boundary*.25;
            }
            if(previous!=null)erode(height,previous);
            double[] moisture=rainfall(height);
            history.add(new TerrainAtlas(seed,settings,height,moisture,ore,mana));
        }
        return List.copyOf(history);
    }
    private static int index(int x,int y) {
        if(y<0){y=-y-1;x+=COLS/2;}if(y>=ROWS){y=2*ROWS-y-1;x+=COLS/2;}
        return y*COLS+Math.floorMod(x,COLS);
    }
    private static double sample(TerrainAtlas previous,Vector p) {
        double x=(Math.atan2(p.z,p.x)+Math.PI)*COLS/(2*Math.PI)-.5;
        double y=(Math.asin(Numbers.clamp(p.y,-1,1))+Math.PI/2)*ROWS/Math.PI-.5;
        int ix=(int)Math.floor(x),iy=(int)Math.floor(y);double fx=x-ix,fy=y-iy;
        return (previous.gridHeight(index(ix,iy))*(1-fx)+previous.gridHeight(index(ix+1,iy))*fx)*(1-fy)
                +(previous.gridHeight(index(ix,iy+1))*(1-fx)+previous.gridHeight(index(ix+1,iy+1))*fx)*fy;
    }
    private static void erode(double[] height,TerrainAtlas previous) {
        // Previous drainage is the lagged runoff network: incision and downstream deposition conserve sediment.
        for(int pass=0;pass<4;pass++) {
            double[] sediment=new double[CELLS];
            for(int i=0;i<CELLS;i++) {
                int next=previous.gridReceiver(i);
                if(next<0||height[next]>=height[i])continue;
                double moved=Math.min(1.5,(height[i]-height[next])*.012*Math.sqrt(previous.gridDrainage(i))*previous.gridMoisture(i));
                sediment[i]-=moved;sediment[next]+=moved;
            }
            for(int i=0;i<CELLS;i++)height[i]+=sediment[i];
        }
    }
    /** Prevailing zonal winds transport ocean vapor; rising ground rains, lee slopes dry out. */
    private static double[] rainfall(double[] height) {
        double[] moisture=new double[CELLS];Arrays.fill(moisture,.45);
        for(int pass=0;pass<32;pass++) {
            double[] next=new double[CELLS];
            for(int i=0;i<CELLS;i++) {
                double lat=-Math.PI/2+Math.PI*(i/COLS+.5)/ROWS;
                int wind=Math.abs(lat)<Math.PI/6?-1:1;
                int upwind=index(i%COLS-wind,i/COLS);
                double rise=height[i]-height[upwind];
                double belt=.38+.16*Math.cos(Math.abs(Math.sin(lat))*3*Math.PI);
                next[i]=height[i]<=TerrainAtlas.SEA_LEVEL?.9:Numbers.clamp(
                        moisture[upwind]*.86+belt*.1+Math.max(0,rise)*.006-Math.max(0,-rise)*.009,.08,.95);
            }
            moisture=next;
        }
        return moisture;
    }
}

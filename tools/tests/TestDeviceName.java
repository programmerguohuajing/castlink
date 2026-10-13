import java.util.*;

public class TestDeviceName {
    //  DeviceName.resolve  Android
    static String resolve(String saved, boolean isTv, String oldDefault, String model) {
        if (saved != null && !saved.isEmpty()) {
            if (isTv) {
                String modelName = "CastLink-" + model.substring(0, Math.min(24, model.length()));
                if (saved.equals(modelName) && !saved.equals(oldDefault)) {
                    return oldDefault; //  CastLink-TV
                }
                return saved;
            }
            if (saved.equals(oldDefault)) {
                return "CastLink-" + model.substring(0, Math.min(24, model.length()));
            }
            return saved;
        }
        return isTv ? oldDefault : "CastLink-" + model;
    }

    static int passed = 0, failed = 0;
    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("PASS: " + name); }
        else { failed++; System.out.println("FAIL: " + name); }
    }

    public static void main(String[] args) {
        String oldDefault = "CastLink-TV";
        String model = "E900V22C";

        // TV v0.1.4  "CastLink-E900V22C"  "CastLink-TV"
        check("TV: mis-migrated name fixed",
            resolve("CastLink-E900V22C", true, oldDefault, model).equals("CastLink-TV"));

        // TV CastLink-TV
        check("TV: correct name kept",
            resolve("CastLink-TV", true, oldDefault, model).equals("CastLink-TV"));

        // TV
        check("TV: custom name kept",
            resolve("MyLivingRoomTV", true, oldDefault, model).equals("MyLivingRoomTV"));

        // 
        check("Phone: old default migrated",
            resolve("CastLink-TV", false, oldDefault, "Galaxy Z Fold3 5G").equals("CastLink-Galaxy Z Fold3 5G"));

        // 
        check("Phone: migrated name kept",
            resolve("CastLink-Galaxy Z Fold3 5G", false, oldDefault, "Galaxy Z Fold3 5G").equals("CastLink-Galaxy Z Fold3 5G"));

        //  TV CastLink-TV
        check("TV: fresh install default",
            resolve(null, true, oldDefault, model).equals("CastLink-TV"));

        //  CastLink-
        check("Phone: fresh install default",
            resolve(null, false, oldDefault, "Pixel 8").equals("CastLink-Pixel 8"));

        System.out.println("\n" + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}

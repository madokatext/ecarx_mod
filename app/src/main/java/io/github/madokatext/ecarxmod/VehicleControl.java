package io.github.madokatext.ecarxmod;

/** File values describe switches, while vehicle properties use the host's enums. */
enum VehicleControl {
    EV_HEV("ev_hev", 0x22040d00, 0x22040d02, 0x22040d01),
    SMART_CHARGE("smart_charge", BatterySocHook.CHARGE_MODE,
            BatterySocHook.MODE_OFF, BatterySocHook.MODE_ACTIVE),
    LOW_SPEED_WARNING("low_speed_warning", 0x201a0100, 0, 1),
    HVAC_CIRCULATION("hvac_circulation", 0x10030100, 0x10030102, 0x10030101);

    // Each host owns its files. Never let both injected processes consume one command.
    static final VehicleControl[] SETTINGS = {EV_HEV, SMART_CHARGE, LOW_SPEED_WARNING};

    final String name;
    final int function;
    final int off;
    final int on;

    VehicleControl(String name, int function, int off, int on) {
        this.name = name;
        this.function = function;
        this.off = off;
        this.on = on;
    }

    String stateValue(int actual) {
        if (actual == on) return "1";
        if (actual == off) return "0";
        // HOLD is a separate mode: the smart-charge switch is off in the stock app.
        if (this == SMART_CHARGE && actual == BatterySocHook.MODE_HOLD) return "0";
        // SAVE and unknown/disconnected data cannot be represented as EV or HEV.
        return "";
    }
}

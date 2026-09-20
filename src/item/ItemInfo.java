package item;

public final class ItemInfo {

    private final String id;
    private final String name;
    private final String iconId;
    private final ActivationMode activationMode;
    private final EffectLifetime effectLifetime;

    public ItemInfo(
            String id,
            String name,
            String iconId,
            ActivationMode activationMode,
            EffectLifetime effectLifetime) {

        this.id = id;
        this.name = name;
        this.iconId = iconId;
        this.activationMode = activationMode;
        this.effectLifetime = effectLifetime;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getIconId() {
        return iconId;
    }

    public ActivationMode getActivationMode() {
        return activationMode;
    }

    public EffectLifetime getEffectLifetime() {
        return effectLifetime;
    }
}
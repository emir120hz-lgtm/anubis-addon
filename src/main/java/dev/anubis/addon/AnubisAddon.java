package dev.anubis.addon;

import dev.anubis.addon.modules.BaseFinder;
import dev.anubis.addon.modules.DeepStorageESP;
import dev.anubis.addon.modules.SusChunks;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;

public class AnubisAddon extends MeteorAddon {
    public static final Category CATEGORY = new Category("Anubis");

    @Override
    public void onInitialize() {
        Modules.get().add(new SusChunks());
        Modules.get().add(new BaseFinder());
        Modules.get().add(new DeepStorageESP());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "dev.anubis.addon";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("4ldenz", "Anubis-client");
    }
}

package com.ender.economy;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.Material;
import java.util.*;

/** Initial playable foundation. Persistent economy data is stored in plugins/EnderEconomy/data.yml. */
public final class EnderEconomy extends JavaPlugin implements Listener, CommandExecutor {
    private final Map<UUID, Double> balances = new HashMap<>();
    private final Map<UUID, UUID> tpaRequests = new HashMap<>(); // target -> requester
    private final Map<UUID, UUID> awaiting = new HashMap<>();   // requester -> target
    private final Map<Integer, Listing> listings = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Integer>> shares = new HashMap<>();
    private final Map<String, Double> companyPrices = new LinkedHashMap<>();
    private final Map<Material, Integer> marketStock = new LinkedHashMap<>();
    private final Map<UUID, String> menuState = new HashMap<>();
    private int nextListingId = 1;
    private NamespacedKey actionKey;
    private static final double AH_FEE_RATE = 0.05;

    private record Listing(int id, UUID seller, Material material, int amount, double price, long createdAt) {}

    @Override public void onEnable() {
        actionKey = new NamespacedKey(this, "action");
        saveDefaultConfig(); loadData();
        for (String cmd : List.of("ah","sell","stocks","casino","tpcasino","pay","balance","tpa","tpaccept","tpdeny","rtpa","rtps","spawn")) {
            Objects.requireNonNull(getCommand(cmd), "Missing command " + cmd).setExecutor(this);
        }
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("EnderEconomy enabled. This is an initial implementation; see README for current scope.");
    }
    @Override public void onDisable() { saveData(); }

    private void loadData() {
        var f = getConfig();
        ConfigurationSection bs = f.getConfigurationSection("balances");
        if (bs != null) for (String k : bs.getKeys(false)) try { balances.put(UUID.fromString(k), bs.getDouble(k)); } catch (Exception ignored) {}
        ConfigurationSection ls = f.getConfigurationSection("listings");
        if (ls != null) for (String k : ls.getKeys(false)) try {
            var s = ls.getConfigurationSection(k); int id = Integer.parseInt(k);
            listings.put(id, new Listing(id, UUID.fromString(s.getString("seller")), Material.valueOf(s.getString("material")), s.getInt("amount"), s.getDouble("price"), s.getLong("created")));
            nextListingId = Math.max(nextListingId, id + 1);
        } catch (Exception ignored) {}
        ConfigurationSection cp = f.getConfigurationSection("companies");
        if (cp != null) for (String n : cp.getKeys(false)) companyPrices.put(n, cp.getDouble(n));
        if (companyPrices.isEmpty()) {
            companyPrices.put("The Ender Auction", 100.0); companyPrices.put("Creeper Credit Union", 75.0);
            companyPrices.put("Redstone Railways", 90.0); companyPrices.put("Emerald Exchange", 110.0);
            companyPrices.put("Obsidian Industries", 125.0);
            for (int i=6; i<=30; i++) companyPrices.put("Company " + i, 50.0 + i * 2.0);
        }
        marketStock.put(Material.CARROT, 1280); marketStock.put(Material.IRON_INGOT, 1000);
        marketStock.put(Material.DIAMOND, 400); marketStock.put(Material.NETHERITE_INGOT, 400);
        ConfigurationSection ms = f.getConfigurationSection("market-stock");
        if (ms != null) for (String n : ms.getKeys(false)) try { marketStock.put(Material.valueOf(n), ms.getInt(n)); } catch (Exception ignored) {}
        ConfigurationSection sh = f.getConfigurationSection("shares");
        if (sh != null) for (String uid : sh.getKeys(false)) try {
            UUID id = UUID.fromString(uid); ConfigurationSection own = sh.getConfigurationSection(uid); Map<String,Integer> m = new HashMap<>();
            if (own != null) for (String n : own.getKeys(false)) m.put(n, own.getInt(n)); shares.put(id,m);
        } catch (Exception ignored) {}
    }
    private void saveData() {
        var f = getConfig(); f.set("balances", null); f.set("listings", null); f.set("companies", null); f.set("market-stock", null); f.set("shares", null);
        balances.forEach((id,v)->f.set("balances."+id, v));
        listings.forEach((id,l)-> { String p="listings."+id; f.set(p+".seller",l.seller.toString()); f.set(p+".material",l.material.name()); f.set(p+".amount",l.amount); f.set(p+".price",l.price); f.set(p+".created",l.createdAt); });
        companyPrices.forEach((n,v)->f.set("companies."+n,v)); marketStock.forEach((m,v)->f.set("market-stock."+m.name(),v));
        shares.forEach((id,own)->own.forEach((n,v)->f.set("shares."+id+"."+n,v)));
        saveConfig();
    }
    private double bal(UUID id) { return balances.getOrDefault(id, 0.0); }
    private void give(UUID id, double amount) { balances.put(id, Math.round((bal(id)+amount)*100.0)/100.0); }
    private boolean charge(Player p, double amount) { if (amount < 0 || bal(p.getUniqueId()) + 1e-9 < amount) return false; give(p.getUniqueId(), -amount); return true; }
    private String money(double n) { return "$" + String.format(Locale.US, "%,.2f", n); }
    private ItemStack icon(Material material, String name, String action, String... lore) {
        ItemStack it = new ItemStack(material); ItemMeta meta = it.getItemMeta(); meta.setDisplayName(ChatColor.GOLD + name);
        List<String> lines = new ArrayList<>(); for (String s : lore) lines.add(ChatColor.GRAY + s); meta.setLore(lines);
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action); it.setItemMeta(meta); return it;
    }
    private void open(Player p, String title, int rows, String state) {
        menuState.put(p.getUniqueId(), state); p.openInventory(Bukkit.createInventory(p, rows*9, ChatColor.DARK_PURPLE + title));
    }
    private void openAh(Player p) {
        open(p,"The Ender Auction",6,"ah"); Inventory inv=p.getOpenInventory().getTopInventory();
        int slot=0;
        for (Listing l : listings.values()) {
            if (l.createdAt < System.currentTimeMillis()-86_400_000L) continue;
            if (slot>=45) break;
            ItemStack it=new ItemStack(l.material, Math.max(1,Math.min(l.amount, l.material.getMaxStackSize()))); ItemMeta m=it.getItemMeta();
            m.setDisplayName(ChatColor.YELLOW+pretty(l.material)); m.setLore(List.of(ChatColor.GRAY+"Quantity: "+l.amount,ChatColor.GRAY+"Price: "+money(l.price),ChatColor.GRAY+"Seller: "+Optional.ofNullable(Bukkit.getOfflinePlayer(l.seller).getName()).orElse("Player"),ChatColor.GREEN+"Click to buy"));
            m.getPersistentDataContainer().set(actionKey,PersistentDataType.STRING,"buy:"+l.id); it.setItemMeta(m); inv.setItem(slot++,it);
        }
        inv.setItem(45,icon(Material.EMERALD,"Create Listing","ah-create","Choose an item from your inventory"));
        inv.setItem(49,icon(Material.BOOK,"Your Listings","ah-own","Manage your listings"));
        inv.setItem(53,icon(Material.BARRIER,"Close","close"));
    }
    private void openSell(Player p) {
        open(p,"Server Sell Shop",6,"sell"); Inventory inv=p.getOpenInventory().getTopInventory();
        int slot=0;
        for (ItemStack stack:p.getInventory().getStorageContents()) if(stack!=null && stack.getType()!=Material.AIR && slot<45) {
            ItemStack display=stack.clone(); ItemMeta m=display.getItemMeta(); m.setDisplayName(ChatColor.YELLOW+pretty(stack.getType()));
            m.setLore(List.of(ChatColor.GRAY+"In inventory: "+stack.getAmount(),ChatColor.GRAY+"Server offer: "+money(sellPrice(stack.getType()))+" each",ChatColor.GREEN+"Click to sell this stack"));
            m.getPersistentDataContainer().set(actionKey,PersistentDataType.STRING,"sell:"+stack.getType().name()); display.setItemMeta(m); inv.setItem(slot++,display);
        }
        inv.setItem(49,icon(Material.BARRIER,"Close","close"));
    }
    private double sellPrice(Material m) {
        if (m==Material.DIAMOND) return 35; if (m==Material.NETHERITE_INGOT) return 180;
        if (m==Material.IRON_INGOT) return 3; if (m==Material.CARROT) return 0.15;
        return Math.max(0.05, Math.min(500, m.getMaxStackSize()==1 ? 2.0 : 0.25));
    }
    private void openStocks(Player p) {
        open(p,"Stocks & Materials",6,"stocks"); Inventory inv=p.getOpenInventory().getTopInventory();
        inv.setItem(10,icon(Material.EMERALD,"Materials Market","materials","Buy and sell actual items"));
        inv.setItem(13,icon(Material.MAP,"Company Exchange","companies","Browse 30 companies"));
        inv.setItem(16,icon(Material.WRITABLE_BOOK,"My Portfolio","portfolio","View your shares"));
        inv.setItem(49,icon(Material.BARRIER,"Close","close"));
    }
    private void openMaterials(Player p) {
        open(p,"Materials Market",6,"materials"); Inventory inv=p.getOpenInventory().getTopInventory(); int i=0;
        for (var e:marketStock.entrySet()) { if(i>=45)break; Material m=e.getKey(); double price=marketPrice(m);
            inv.setItem(i++,icon(m,pretty(m),"material:"+m.name(),"Shared stock: "+e.getValue(),"Buy price: "+money(price),"Sell price: "+money(price*0.75),"Click to buy 1")); }
        inv.setItem(49,icon(Material.ARROW,"Back","stocks"));
    }
    private double marketPrice(Material m) { double base=sellPrice(m)*1.5; int stock=marketStock.getOrDefault(m,0); return Math.max(0.05,base*(stock<100?2.0:stock<500?1.3:1.0)); }
    private void openCompanies(Player p, int page) {
        open(p,"Company Exchange • Page "+(page+1),6,"companies:"+page); Inventory inv=p.getOpenInventory().getTopInventory();
        List<String> names=new ArrayList<>(companyPrices.keySet()); int start=page*45;
        for(int i=0;i<45 && start+i<names.size();i++){String n=names.get(start+i); inv.setItem(i,icon(Material.GOLD_INGOT,n,"company:"+n,"Share price: "+money(companyPrices.get(n)),"Click to buy 1 share"));}
        if(page>0) inv.setItem(45,icon(Material.ARROW,"Previous page","company-page:"+(page-1)));
        if(start+45<names.size()) inv.setItem(53,icon(Material.ARROW,"Next page","company-page:"+(page+1)));
        inv.setItem(49,icon(Material.BARREL,"Back","stocks"));
    }
    private void openCompany(Player p,String name) {
        open(p,name,3,"company-detail:"+name); Inventory inv=p.getOpenInventory().getTopInventory(); double price=companyPrices.getOrDefault(name,50.0);
        inv.setItem(11,icon(Material.EMERALD,"Buy 1 share","share-buy:"+name,"Price: "+money(price)));
        inv.setItem(15,icon(Material.REDSTONE,"Sell 1 share","share-sell:"+name,"Price: "+money(price*0.95)));
        int owned=shares.getOrDefault(p.getUniqueId(),Map.of()).getOrDefault(name,0);
        inv.setItem(13,icon(Material.GOLD_INGOT,name,"none","Share price: "+money(price),"You own: "+owned+" shares","Company prices change with performance"));
        inv.setItem(22,icon(Material.ARROW,"Back to companies","companies:0"));
    }
    private void openCasino(Player p) {
        open(p,"Virtual Casino",3,"casino"); Inventory inv=p.getOpenInventory().getTopInventory();
        inv.setItem(11,icon(Material.SUNFLOWER,"Lucky Spin","spin","Cost: $10","Random prize or loss"));
        inv.setItem(15,icon(Material.REDSTONE,"Roulette: Red / Black","roulette","Cost: $10","Choose red or black in chat is not yet supported; click to play red"));
        inv.setItem(22,icon(Material.BARRIER,"Close","close"));
    }
    private String pretty(Material m) { String s=m.name().toLowerCase(Locale.ROOT).replace('_',' '); StringBuilder b=new StringBuilder(); for(String w:s.split(" ")) b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' '); return b.toString().trim(); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String c=command.getName().toLowerCase(Locale.ROOT);
        if (!(sender instanceof Player p)) { sender.sendMessage("This command requires a player."); return true; }
        switch(c) {
            case "balance" -> p.sendMessage(ChatColor.GREEN+"Balance: "+money(bal(p.getUniqueId())));
            case "pay" -> { if(args.length!=2){p.sendMessage("Usage: /pay <player> <amount>");return true;} Player target=Bukkit.getPlayerExact(args[0]); double amount=parseAmount(args[1]); if(target==null||target.equals(p)||amount<=0||!charge(p,amount)){p.sendMessage(ChatColor.RED+"Invalid player/amount or insufficient funds.");return true;} give(target.getUniqueId(),amount); p.sendMessage("Paid "+money(amount)+" to "+target.getName()+"."); target.sendMessage("Received "+money(amount)+" from "+p.getName()+"."); }
            case "ah" -> openAh(p);
            case "sell" -> openSell(p);
            case "stocks" -> openStocks(p);
            case "casino" -> openCasino(p);
            case "tpcasino" -> { Location loc=getConfig().getLocation("casino-location"); if(loc==null){p.sendMessage(ChatColor.RED+"Casino location is not configured yet. An admin must set it.");}else p.teleport(loc); }
            case "spawn" -> p.teleport(p.getWorld().getSpawnLocation());
            case "tpa" -> { if(args.length!=1){p.sendMessage("Usage: /tpa <player>");return true;} Player target=Bukkit.getPlayerExact(args[0]); if(target==null||target.equals(p)){p.sendMessage("Player not found.");return true;} tpaRequests.put(target.getUniqueId(),p.getUniqueId()); awaiting.put(p.getUniqueId(),target.getUniqueId()); target.sendMessage(p.getName()+" requests to teleport to you. Use /tpaccept or /tpdeny."); p.sendMessage("Teleport request sent to "+target.getName()+"."); }
            case "tpaccept" -> { UUID req=tpaRequests.remove(p.getUniqueId()); if(req==null){p.sendMessage("No pending request.");return true;} Player requester=Bukkit.getPlayer(req); if(requester==null){p.sendMessage("That player is offline.");return true;} requester.teleport(p.getLocation()); awaiting.remove(req); requester.sendMessage("Teleporting to "+p.getName()+"."); p.sendMessage("Accepted teleport request."); }
            case "tpdeny" -> { UUID req=tpaRequests.remove(p.getUniqueId()); if(req==null){p.sendMessage("No pending request.");return true;} Player requester=Bukkit.getPlayer(req); if(requester!=null){requester.sendMessage(p.getName()+" denied your teleport request.");awaiting.remove(req);} p.sendMessage("Request denied."); }
            case "rtpa" -> randomTeleport(p,10000);
            case "rtps" -> randomTeleport(p,10000);
        }
        return true;
    }
    private double parseAmount(String s){try{return Double.parseDouble(s);}catch(Exception e){return -1;}}
    private void randomTeleport(Player p,int radius) {
        World w=p.getWorld(); Location spawn=w.getSpawnLocation(); Random r=new Random();
        for(int tries=0;tries<40;tries++){int x=spawn.getBlockX()+r.nextInt(radius*2+1)-radius; int z=spawn.getBlockZ()+r.nextInt(radius*2+1)-radius; int y=w.getHighestBlockYAt(x,z)+1;
            Location loc=new Location(w,x+0.5,y,z+0.5); if(y>0&&y<w.getMaxHeight()-2&&loc.getBlock().isPassable()&&loc.clone().add(0,1,0).getBlock().isPassable()&&loc.clone().subtract(0,1,0).getBlock().getType().isSolid()){p.teleport(loc);p.sendMessage("Random teleport complete.");return;}}
        p.sendMessage(ChatColor.RED+"Couldn't find a safe location. Try again.");
    }
    @EventHandler public void onClick(InventoryClickEvent e) {
        if(!(e.getWhoClicked() instanceof Player p))return; String state=menuState.get(p.getUniqueId()); if(state==null)return;
        if(e.getView().getTopInventory()!=e.getClickedInventory() && e.getClickedInventory()!=null)return;
        if(e.getClickedInventory()==null || e.getClickedInventory()!=e.getView().getTopInventory())return;
        e.setCancelled(true); ItemStack it=e.getCurrentItem(); if(it==null||!it.hasItemMeta())return;
        String a=it.getItemMeta().getPersistentDataContainer().get(actionKey,PersistentDataType.STRING); if(a==null)return;
        if(a.equals("close")){p.closeInventory();return;}
        if(a.equals("stocks")){openStocks(p);return;} if(a.equals("materials")){openMaterials(p);return;} if(a.equals("companies")){openCompanies(p,0);return;} if(a.startsWith("company-page:")){openCompanies(p,Integer.parseInt(a.split(":")[1]));return;}
        if(a.startsWith("company:")){openCompany(p,a.substring(8));return;} if(a.equals("portfolio")){p.closeInventory();Map<String,Integer> own=shares.getOrDefault(p.getUniqueId(),Map.of());p.sendMessage(ChatColor.GOLD+"Your shares:");if(own.isEmpty())p.sendMessage("No shares owned yet.");else own.forEach((n,v)->p.sendMessage(n+": "+v+" shares"));return;}
        if(a.startsWith("share-buy:")){String n=a.substring(10);double price=companyPrices.getOrDefault(n,50.0);if(!charge(p,price)){p.sendMessage(ChatColor.RED+"Insufficient funds.");return;}shares.computeIfAbsent(p.getUniqueId(),k->new HashMap<>()).merge(n,1,Integer::sum);p.sendMessage("Bought 1 share of "+n+" for "+money(price)+".");openCompany(p,n);saveData();return;}
        if(a.startsWith("share-sell:")){String n=a.substring(11);Map<String,Integer> own=shares.getOrDefault(p.getUniqueId(),Map.of());if(own.getOrDefault(n,0)<1){p.sendMessage(ChatColor.RED+"You don't own a share of this company.");return;}own.put(n,own.get(n)-1);double price=companyPrices.getOrDefault(n,50.0)*0.95;give(p.getUniqueId(),price);p.sendMessage("Sold 1 share for "+money(price)+".");openCompany(p,n);saveData();return;}
        if(a.startsWith("material:")){Material m=Material.valueOf(a.substring(9));double price=marketPrice(m);if(marketStock.getOrDefault(m,0)<1||!charge(p,price)){p.sendMessage(ChatColor.RED+"Out of stock or insufficient funds.");return;}marketStock.put(m,marketStock.get(m)-1);p.getInventory().addItem(new ItemStack(m));p.sendMessage("Bought 1 "+pretty(m)+" for "+money(price)+".");openMaterials(p);saveData();return;}
        if(a.equals("spin")){if(!charge(p,10)){p.sendMessage("You need $10 to play.");return;}double roll=Math.random();if(roll<0.12){give(p.getUniqueId(),50);p.sendMessage(ChatColor.GREEN+"Lucky Spin: you won $50!");}else if(roll<0.42){give(p.getUniqueId(),15);p.sendMessage("Lucky Spin: you won $15!");}else p.sendMessage(ChatColor.RED+"Lucky Spin: no prize this time.");openCasino(p);saveData();return;}
        if(a.equals("roulette")){if(!charge(p,10)){p.sendMessage("You need $10 to play.");return;}if(Math.random()<0.47){give(p.getUniqueId(),20);p.sendMessage("Roulette landed red! You won $20.");}else p.sendMessage("Roulette didn't land red. Better luck next time.");openCasino(p);saveData();return;}
        if(a.equals("ah-create")){p.closeInventory();p.sendMessage(ChatColor.YELLOW+"To list an item in this initial build, hold it and use /ahlist <price> <amount>. The full chest-based listing flow is not implemented yet.");return;}
        if(a.equals("ah-own")){p.closeInventory();p.sendMessage("Your active listings are shown in the Auction House in a later build.");return;}
        if(a.startsWith("buy:")){int id=Integer.parseInt(a.substring(4));Listing l=listings.get(id);if(l==null){p.sendMessage("Listing unavailable.");openAh(p);return;}if(l.seller.equals(p.getUniqueId())){p.sendMessage("You can't buy your own listing.");return;}if(!charge(p,l.price)){p.sendMessage("Insufficient funds.");return;}Player seller=Bukkit.getPlayer(l.seller);double fee=l.price*AH_FEE_RATE;give(l.seller,l.price-fee); // Initial fee is retained by server; shareholder distributions are a later stage.
            ItemStack bought=new ItemStack(l.material,l.amount);Map<Integer,ItemStack> overflow=p.getInventory().addItem(bought);overflow.values().forEach(x->p.getWorld().dropItemNaturally(p.getLocation(),x));listings.remove(id);p.sendMessage("Purchased item for "+money(l.price)+". Auction fee: "+money(fee)+".");if(seller!=null)seller.sendMessage("Your listing sold for "+money(l.price)+"; fee: "+money(fee)+".");openAh(p);saveData();return;}
        if(a.startsWith("sell:")){Material m=Material.valueOf(a.substring(5));int count=0;for(ItemStack stack:p.getInventory().getStorageContents())if(stack!=null&&stack.getType()==m)count+=stack.getAmount();if(count<=0)return;double payout=count*sellPrice(m);for(int i=0;i<p.getInventory().getStorageContents().length;i++){ItemStack stack=p.getInventory().getItem(i);if(stack!=null&&stack.getType()==m)p.getInventory().setItem(i,null);}give(p.getUniqueId(),payout);p.sendMessage("Sold "+count+" "+pretty(m)+" for "+money(payout)+".");openSell(p);saveData();return;}
    }
    @EventHandler public void onQuit(PlayerQuitEvent e) { menuState.remove(e.getPlayer().getUniqueId()); }
}

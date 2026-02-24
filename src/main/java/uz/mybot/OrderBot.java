package uz.mybot;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class OrderBot extends TelegramLongPollingBot {

    private static final String BOT_TOKEN    = "8451203409:AAF2M_5Ks-3mL5dsUfuRf77R20ENbg3CMbk";
    private static final String BOT_USERNAME = "Beznisforyoubot";

    // ❗ Guruh CHAT_ID sini shu yerga yozing (manfiy son bo'ladi, masalan: -1001234567890)
    // Qanday topish: botni guruhga qo'shing, /get_id yuboring yoki @userinfobot dan foydalaning
    private static final long ADMIN_GROUP_ID = -1003774646712L;

    private static final int MIN_ORDER = 7;
    // Buyurtma tartib raqami
    private int orderCounter = 1;

    // ─── Discount tiers ───────────────────────────────────────────────────────
    // qty >= 50 → 5 bonus, qty >= 25 → 3 bonus, qty >= 15 → 2 bonus
    private static int calcBonus(int qty) {
        if (qty >= 50) return 5;
        if (qty >= 25) return 3;
        if (qty >= 15) return 2;
        return 0;
    }

    // ─── States ───────────────────────────────────────────────────────────────
    enum State {
        START_MENU,
        GET_NAME, GET_SURNAME, GET_PHONE, GET_EXTRA_PHONE, GET_LOCATION,
        MAIN_MENU,
        ORDER_QUANTITY,
        // Edit states
        EDIT_MENU,
        EDIT_NAME, EDIT_SURNAME, EDIT_PHONE, EDIT_EXTRA_PHONE, EDIT_LOCATION, EDIT_QUANTITY,
        FEEDBACK_TEXT,
        COMPLAINT_TEXT,
        RATING_AWAIT
    }

    // ─── Per-user data ────────────────────────────────────────────────────────
    static class UserData {
        String name, surname, phone, extraPhone, location;
        int lastOrderQty = 0;
        State state = State.START_MENU;
        boolean registered = false;
    }

    // ─── Public ratings storage (shared across all users) ─────────────────────
    // Each entry: {userId, stars (1-5), comment}
    static class RatingEntry {
        long userId;
        String userName;
        int stars;
        String comment;

        RatingEntry(long userId, String userName, int stars, String comment) {
            this.userId = userId;
            this.userName = userName;
            this.stars = stars;
            this.comment = comment;
        }
    }

    private final Map<Long, UserData> users = new ConcurrentHashMap<>();
    // Public ratings list (newest first)
    private final List<RatingEntry> ratings = Collections.synchronizedList(new ArrayList<>());
    // To avoid duplicate rating from same user (optional: allow update)
    private final Map<Long, Integer> userRatingIndex = new ConcurrentHashMap<>();

    // ─── Bot identity ─────────────────────────────────────────────────────────
    @Override public String getBotToken()    { return BOT_TOKEN; }
    @Override public String getBotUsername() { return BOT_USERNAME; }



    // ─── Validation ───────────────────────────────────────────────────────────
    private boolean isValidNameOrSurname(String s) {
        if (s == null) return false;
        s = s.trim();
        if (s.length() < 2) return false;
        return s.matches("^[\\p{L}]+([\\p{L}\\s\\-']*[\\p{L}])?$");
    }

    private String normalizePhone(String input) {
        if (input == null) return "";
        input = input.trim();
        String cleaned = input.replaceAll("[^\\d+]", "");
        if (cleaned.chars().filter(ch -> ch == '+').count() > 1) return "";
        if (!cleaned.startsWith("+")) cleaned = cleaned.replaceAll("\\D", "");
        return cleaned;
    }

    private boolean isValidUzPhone(String raw) {
        String p = normalizePhone(raw);
        if (p.isEmpty()) return false;
        if (p.startsWith("+998") && p.length() == 13) return p.substring(4).matches("\\d{9}");
        return p.matches("\\d{9}");
    }

    private String formatUzPhone(String raw) {
        String p = normalizePhone(raw);
        if (p.matches("\\d{9}")) return "+998" + p;
        if (p.startsWith("+998") && p.length() == 13 && p.substring(4).matches("\\d{9}")) return p;
        return "";
    }

    // ─── Entry point ──────────────────────────────────────────────────────────
    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasMessage())           handleMessage(update);
            else if (update.hasCallbackQuery()) handleCallback(update);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    // ─── Message handler ──────────────────────────────────────────────────────
    private void handleMessage(Update update) throws TelegramApiException {
        long chatId  = update.getMessage().getChatId();
        String text  = update.getMessage().hasText() ? update.getMessage().getText() : "";
        String firstName = update.getMessage().getFrom().getFirstName();

        if (text != null && text.startsWith("/start")) {
            UserData ud = new UserData();
            users.put(chatId, ud);
            sendRemoveKeyboard(chatId, "Assalomu alaykum! Quyidagilardan birini tanlang:");
            sendStartMenu(chatId);
            return;
        }

        UserData data = users.get(chatId);
        if (data == null) {
            send(chatId, "Boshlash uchun /start bosing.", null, null);
            return;
        }

        switch (data.state) {

            // ── Registration ──────────────────────────────────────────────────
            case START_MENU -> sendStartMenu(chatId);

            case GET_NAME -> {
                if (!isValidNameOrSurname(text)) { send(chatId,"❌ Ism noto'g'ri. Qaytadan kiriting:",null,null); return; }
                data.name = text.trim(); data.state = State.GET_SURNAME;
                send(chatId,"Familiyangizni kiriting:",null,null);
            }
            case GET_SURNAME -> {
                if (!isValidNameOrSurname(text)) { send(chatId,"❌ Familiya noto'g'ri. Qaytadan kiriting:",null,null); return; }
                data.surname = text.trim(); data.state = State.GET_PHONE;
                send(chatId,"Telefon raqamingizni yuboring:\nMasalan: +998901234567 yoki 901234567",null,phoneKeyboard());
            }
            case GET_PHONE -> {
                String phoneRaw = update.getMessage().hasContact()
                        ? update.getMessage().getContact().getPhoneNumber() : text.trim();
                if (!isValidUzPhone(phoneRaw)) { send(chatId,"❌ Telefon raqam noto'g'ri.\nFormat: +998901234567 yoki 901234567",null,phoneKeyboard()); return; }
                data.phone = formatUzPhone(phoneRaw); data.state = State.GET_EXTRA_PHONE;
                sendRemoveKeyboard(chatId,"Qo'shimcha telefon raqam kiriting (majburiy):\nMasalan: +998901234567 yoki 901234567");
            }
            case GET_EXTRA_PHONE -> {
                if (!isValidUzPhone(text)) { send(chatId,"❌ Qo'shimcha telefon raqam noto'g'ri.\nQaytadan kiriting:",null,null); return; }
                data.extraPhone = formatUzPhone(text.trim()); data.state = State.GET_LOCATION;
                send(chatId,"Lokatsiyangizni yuboring:\n📍 Tugma orqali real joylashuv yoki manzilni matn ko'rinishida yozing:",null,locationKeyboard());
            }
            case GET_LOCATION -> {
                if (update.getMessage().hasLocation()) {
                    var loc = update.getMessage().getLocation();
                    data.location = "https://maps.google.com/?q=" + loc.getLatitude() + "," + loc.getLongitude();
                } else {
                    data.location = text.trim();
                }
                if (data.location == null || data.location.isBlank()) {
                    send(chatId,"❌ Lokatsiya bo'sh bo'lishi mumkin emas. Qayta yuboring:",null,locationKeyboard()); return;
                }
                data.registered = true; data.state = State.MAIN_MENU;
                sendRemoveKeyboard(chatId,"✅ Ro'yxatdan o'tdingiz, " + data.name + "!");
                sendMainMenu(chatId);
            }

            // ── Main menu ─────────────────────────────────────────────────────
            case MAIN_MENU -> sendMainMenu(chatId);

            // ── Order ─────────────────────────────────────────────────────────
            case ORDER_QUANTITY -> {
                if (!text.trim().matches("\\d+")) { send(chatId,"Faqat raqam kiriting!",null,null); return; }
                int qty = Integer.parseInt(text.trim());
                if (qty < MIN_ORDER) { send(chatId,"Minimal buyurtma " + MIN_ORDER + " ta! " + MIN_ORDER + " yoki undan ko'p kiriting.",null,null); return; }
                data.lastOrderQty = qty;
                data.state = State.MAIN_MENU;
                int bonus = calcBonus(qty);
                String bonusLine = bonus > 0 ? "\n🎁 Bonus: +" + bonus + " ta (chegirma tarifi)" : "";

                // Buyurtma raqami va vaqti
                int orderNum = orderCounter++;
                String time = LocalDateTime.now(ZoneId.of("Asia/Tashkent"))
                        .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));

                String summary = "✅ Buyurtmangiz qabul qilindi!\n\n"
                        + "🔢 Buyurtma #" + orderNum + "\n"
                        + "🕐 Vaqt: " + time + "\n"
                        + "👤 Ism: " + data.name + " " + data.surname + "\n"
                        + "📞 Tel: " + data.phone + "\n"
                        + "📞 Qo'shimcha: " + data.extraPhone + "\n"
                        + "📍 Lokatsiya: " + data.location + "\n"
                        + "🛒 Miqdor: " + qty + " ta" + bonusLine + "\n\n"
                        + "📲 Aloqa uchun: +998777167479";

                // Xaridorga yuborish
                send(chatId, summary, null, null);

                // Adminga guruhga yuborish
                String adminMsg = "🆕 YANGI BUYURTMA #" + orderNum + "\n"
                        + "━━━━━━━━━━━━━━━━━━━\n"
                        + "🕐 Vaqt: " + time + "\n"
                        + "👤 Mijoz: " + data.name + " " + data.surname + "\n"
                        + "📞 Tel: " + data.phone + "\n"
                        + "📞 Qo'shimcha: " + data.extraPhone + "\n"
                        + "📍 Lokatsiya: " + data.location + "\n"
                        + "🛒 Miqdor: " + qty + " ta" + bonusLine + "\n"
                        + "🆔 Telegram ID: " + chatId;
                sendToAdminGroup(adminMsg);

                sendAfterOrderMenu(chatId);
            }

            // ── Edit fields ───────────────────────────────────────────────────
            case EDIT_NAME -> {
                if (!isValidNameOrSurname(text)) { send(chatId,"❌ Ism noto'g'ri. Qaytadan kiriting:",null,null); return; }
                data.name = text.trim(); data.state = State.EDIT_MENU;
                send(chatId,"✅ Ism yangilandi: " + data.name,null,null); sendEditMenu(chatId, data);
            }
            case EDIT_SURNAME -> {
                if (!isValidNameOrSurname(text)) { send(chatId,"❌ Familiya noto'g'ri. Qaytadan kiriting:",null,null); return; }
                data.surname = text.trim(); data.state = State.EDIT_MENU;
                send(chatId,"✅ Familiya yangilandi: " + data.surname,null,null); sendEditMenu(chatId, data);
            }
            case EDIT_PHONE -> {
                String pr = update.getMessage().hasContact()
                        ? update.getMessage().getContact().getPhoneNumber() : text.trim();
                if (!isValidUzPhone(pr)) { send(chatId,"❌ Noto'g'ri telefon. Qaytadan:",null,phoneKeyboard()); return; }
                data.phone = formatUzPhone(pr); data.state = State.EDIT_MENU;
                sendRemoveKeyboard(chatId,"✅ Telefon yangilandi: " + data.phone); sendEditMenu(chatId, data);
            }
            case EDIT_EXTRA_PHONE -> {
                if (!isValidUzPhone(text)) { send(chatId,"❌ Noto'g'ri telefon. Qaytadan:",null,null); return; }
                data.extraPhone = formatUzPhone(text.trim()); data.state = State.EDIT_MENU;
                send(chatId,"✅ Qo'shimcha telefon yangilandi: " + data.extraPhone,null,null); sendEditMenu(chatId, data);
            }
            case EDIT_LOCATION -> {
                if (update.getMessage().hasLocation()) {
                    var loc = update.getMessage().getLocation();
                    data.location = "https://maps.google.com/?q=" + loc.getLatitude() + "," + loc.getLongitude();
                } else {
                    data.location = text.trim();
                }
                if (data.location == null || data.location.isBlank()) {
                    send(chatId,"❌ Lokatsiya bo'sh bo'lishi mumkin emas:",null,locationKeyboard()); return;
                }
                data.state = State.EDIT_MENU;
                sendRemoveKeyboard(chatId,"✅ Lokatsiya yangilandi!"); sendEditMenu(chatId, data);
            }
            case EDIT_QUANTITY -> {
                if (!text.trim().matches("\\d+")) { send(chatId,"Faqat raqam kiriting!",null,null); return; }
                int qty = Integer.parseInt(text.trim());
                if (qty < MIN_ORDER) { send(chatId,"Minimal buyurtma " + MIN_ORDER + " ta!",null,null); return; }
                data.lastOrderQty = qty; data.state = State.EDIT_MENU;
                int bonus = calcBonus(qty);
                String bonusLine = bonus > 0 ? "\n🎁 Bonus: +" + bonus + " ta" : "";
                send(chatId,"✅ Miqdor yangilandi: " + qty + " ta" + bonusLine,null,null);
                sendEditMenu(chatId, data);
            }

            // ── Feedback & Complaint ──────────────────────────────────────────
            case FEEDBACK_TEXT -> {
                data.state = State.MAIN_MENU;
                String time3 = LocalDateTime.now(ZoneId.of("Asia/Tashkent"))
                        .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
                send(chatId,"✅ Fikr-mulohazangiz qabul qilindi!\n\n«" + text.trim() + "»\n\nRahmat! 🙏",null,null);
                sendToAdminGroup("💬 YANGI FIKr-MULOHAZA\n━━━━━━━━━━━━━━━━━━━\n"
                        + "🕐 Vaqt: " + time3 + "\n"
                        + "👤 Mijoz: " + nvl(data.name) + " " + nvl(data.surname) + "\n"
                        + "📞 Tel: " + nvl(data.phone) + "\n"
                        + "🆔 Telegram ID: " + chatId + "\n"
                        + "📝 Fikr:\n" + text.trim());
                sendMainMenu(chatId);
            }
            case COMPLAINT_TEXT -> {
                data.state = State.MAIN_MENU;
                String time2 = LocalDateTime.now(ZoneId.of("Asia/Tashkent"))
                        .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
                send(chatId,"📋 Shikoyatingiz qabul qilindi!\n\n«" + text.trim() + "»\n\nKo'rib chiqib, siz bilan bog'lanamiz. Rahmat!",null,null);
                // Adminga yuborish
                sendToAdminGroup("📋 YANGI SHIKOYAT\n━━━━━━━━━━━━━━━━━━━\n"
                        + "🕐 Vaqt: " + time2 + "\n"
                        + "👤 Mijoz: " + nvl(data.name) + " " + nvl(data.surname) + "\n"
                        + "📞 Tel: " + nvl(data.phone) + "\n"
                        + "🆔 Telegram ID: " + chatId + "\n"
                        + "📝 Shikoyat:\n" + text.trim());
                sendMainMenu(chatId);
            }

            // ── Rating ────────────────────────────────────────────────────────
            case RATING_AWAIT -> {
                // handled by callback (star buttons), ignore plain text
                send(chatId,"Yulduzcha tugmalardan birini bosing ⭐",ratingKeyboard(),null);
            }
        }
    }

    // ─── Callback handler ─────────────────────────────────────────────────────
    private void handleCallback(Update update) throws TelegramApiException {
        long chatId  = update.getCallbackQuery().getMessage().getChatId();
        String cb    = update.getCallbackQuery().getData();
        String uName = update.getCallbackQuery().getFrom().getFirstName();

        UserData user = users.get(chatId);
        if (user == null) return;

        // ── Start menu ──────────────────────────────────────────────────────
        if ("start_order".equals(cb)) {
            user.state = State.GET_NAME;
            send(chatId,"Ismingizni kiriting:",null,null);
            return;
        }
        if ("leaders".equals(cb)) {
            String info = "👥 Mahallaning ma'sul shaxslari:\n\n"
                    + "1) Mahalla raisi: Ism Familiya\n 📞 +998xx xxx xx xx\n\n"
                    + "2) Profilaktika inspektori: Ism Familiya\n 📞 +998xx xxx xx xx\n\n"
                    + "3) Yoshlar yetakchisi: Ism Familiya\n 📞 +998xx xxx xx xx\n\n"
                    + "Kerakli bo'lsa, yozib qoldiring — siz bilan bog'lanishadi.";
            send(chatId, info, backToStartMenuKeyboard(), null);
            return;
        }
        if ("back_start".equals(cb)) {
            user.state = State.START_MENU;
            send(chatId,"Quyidagilardan birini tanlang:", startMenuKeyboard(), null);
            return;
        }

        // ── Main menu actions ───────────────────────────────────────────────
        if ("order".equals(cb)) {
            user.state = State.ORDER_QUANTITY;
            send(chatId,"Nechta mahsulot buyurtma berasiz?\n(Minimal: " + MIN_ORDER + " ta)",null,null);
            return;
        }
        if ("feedback".equals(cb)) {
            user.state = State.FEEDBACK_TEXT;
            send(chatId,"💬 Fikr-mulohazangizni yozing:",null,null);
            return;
        }
        if ("complaint".equals(cb)) {
            user.state = State.COMPLAINT_TEXT;
            send(chatId,"📋 Shikoyat arizangizni yozing:\n(Mahsulot sifati, yetkazib berish va boshqa muammolar haqida):",null,null);
            return;
        }
        if ("discounts".equals(cb)) {
            String disc = "🎁 Chegirmalar va Bonus tizimi:\n\n"
                    + "🛒 15 ta mahsulot → +2 ta BONUS\n"
                    + "🛒 25 ta mahsulot → +3 ta BONUS\n"
                    + "🛒 50 ta mahsulot → +5 ta BONUS\n\n"
                    + "Buyurtma berganda bonus avtomatik hisoblanadi!\n"
                    + "📲 Batafsil: +998777167479";
            send(chatId, disc, backToMainMenuKeyboard(), null);
            return;
        }
        if ("back_main".equals(cb)) {
            user.state = State.MAIN_MENU;
            sendMainMenu(chatId);
            return;
        }

        // ── Edit ────────────────────────────────────────────────────────────
        if ("edit_profile".equals(cb)) {
            user.state = State.EDIT_MENU;
            sendEditMenu(chatId, user);
            return;
        }
        if ("edit_name".equals(cb))       { user.state = State.EDIT_NAME;       send(chatId,"Yangi ismingizni kiriting:",null,null); return; }
        if ("edit_surname".equals(cb))    { user.state = State.EDIT_SURNAME;    send(chatId,"Yangi familiyangizni kiriting:",null,null); return; }
        if ("edit_phone".equals(cb))      { user.state = State.EDIT_PHONE;      send(chatId,"Yangi telefon raqamingizni kiriting:",null,phoneKeyboard()); return; }
        if ("edit_extra_phone".equals(cb)){ user.state = State.EDIT_EXTRA_PHONE;send(chatId,"Yangi qo'shimcha telefon kiriting:",null,null); return; }
        if ("edit_location".equals(cb))   { user.state = State.EDIT_LOCATION;   send(chatId,"Yangi lokatsiyangizni yuboring:",null,locationKeyboard()); return; }
        if ("edit_quantity".equals(cb))   { user.state = State.EDIT_QUANTITY;   send(chatId,"Yangi buyurtma miqdorini kiriting:",null,null); return; }
        if ("back_edit".equals(cb))       { sendEditMenu(chatId, user); return; }

        // ── Ratings ─────────────────────────────────────────────────────────
        if ("rate_product".equals(cb)) {
            user.state = State.RATING_AWAIT;
            send(chatId,"⭐ Mahsulotni baholang (1-5 yulduz):", ratingKeyboard(), null);
            return;
        }
        if (cb.startsWith("rate_")) {
            int stars = Integer.parseInt(cb.replace("rate_", ""));
            user.state = State.MAIN_MENU;
            // Save rating
            RatingEntry entry = new RatingEntry(chatId, uName, stars, "");
            Integer existing = userRatingIndex.get(chatId);
            if (existing != null) {
                ratings.set(existing, entry);
            } else {
                ratings.add(0, entry);
                userRatingIndex.put(chatId, 0);
                // update indices for all others
                for (Map.Entry<Long, Integer> e : userRatingIndex.entrySet()) {
                    if (!e.getKey().equals(chatId)) e.setValue(e.getValue() + 1);
                }
            }
            String stars_str = "⭐".repeat(stars) + "☆".repeat(5 - stars);
            send(chatId,"✅ Bahoyingiz qabul qilindi! " + stars_str + "\nRahmat!",null,null);
            sendMainMenu(chatId);
            return;
        }
        if ("view_ratings".equals(cb)) {
            send(chatId, buildRatingsText(), backToMainMenuKeyboard(), null);
            return;
        }
    }

    // ─── Ratings display ──────────────────────────────────────────────────────
    private String buildRatingsText() {
        if (ratings.isEmpty()) return "⭐ Hali baholashlar yo'q.\nBirinchi baholovchi bo'ling!";
        // Compute average
        double avg = ratings.stream().mapToInt(r -> r.stars).average().orElse(0);
        StringBuilder sb = new StringBuilder();
        sb.append("⭐ Umumiy reyting: ").append(String.format("%.1f", avg))
                .append("/5.0 (").append(ratings.size()).append(" ta baholash)\n\n");
        sb.append("📋 So'nggi baholar:\n");
        int limit = Math.min(ratings.size(), 10);
        for (int i = 0; i < limit; i++) {
            RatingEntry r = ratings.get(i);
            String stars = "⭐".repeat(r.stars) + "☆".repeat(5 - r.stars);
            sb.append("• ").append(r.userName != null ? r.userName : "Foydalanuvchi")
                    .append(": ").append(stars).append("\n");
        }
        return sb.toString();
    }

    // ─── Keyboards ────────────────────────────────────────────────────────────
    private InlineKeyboardMarkup startMenuKeyboard() {
        return inlineMarkup(
                btn("🛒 Mahsulotga buyurtma berish", "start_order"),
                btn("👥 Mahallaning ma'sul shaxslari", "leaders"),
                btn("🎁 Chegirmalar", "discounts"),
                btn("⭐ Mahsulot reytingi", "view_ratings")
        );
    }

    private void sendStartMenu(long chatId) throws TelegramApiException {
        send(chatId,"👇 Tanlang:", startMenuKeyboard(), null);
    }

    private void sendMainMenu(long chatId) throws TelegramApiException {
        InlineKeyboardMarkup markup = inlineMarkup(
                btn("🛒 Mahsulot buyurtma berish", "order"),
                btn("✏️ Ma'lumotlarni tahrirlash", "edit_profile"),
                btn("🎁 Chegirmalar", "discounts"),
                btn("⭐ Baholash / Reyting ko'rish", "view_ratings"),
                btn("⭐ Mahsulotni baholash", "rate_product"),
                btn("💬 Fikr-mulohaza", "feedback"),
                btn("📋 Shikoyat ariza", "complaint")
        );
        send(chatId,"Nima qilmoqchisiz?", markup, null);
    }

    private void sendAfterOrderMenu(long chatId) throws TelegramApiException {
        InlineKeyboardMarkup markup = inlineMarkup(
                btn("✏️ Buyurtmani tahrirlash", "edit_profile"),
                btn("⭐ Mahsulotni baholash", "rate_product"),
                btn("📋 Shikoyat ariza", "complaint"),
                btn("💬 Fikr-mulohaza", "feedback"),
                btn("🏠 Asosiy menyu", "back_main")
        );
        send(chatId,"Qo'shimcha amal:", markup, null);
    }

    private void sendEditMenu(long chatId, UserData d) throws TelegramApiException {
        String info = "✏️ Tahrirlash menyusi\n\n"
                + "👤 Ism: " + nvl(d.name) + "\n"
                + "👤 Familiya: " + nvl(d.surname) + "\n"
                + "📞 Tel: " + nvl(d.phone) + "\n"
                + "📞 Qo'shimcha: " + nvl(d.extraPhone) + "\n"
                + "📍 Lokatsiya: " + nvl(d.location) + "\n"
                + "🛒 Oxirgi buyurtma: " + (d.lastOrderQty > 0 ? d.lastOrderQty + " ta" : "yo'q") + "\n\n"
                + "Nimani o'zgartirmoqchisiz?";
        InlineKeyboardMarkup markup = inlineMarkup(
                btn("✏️ Ism", "edit_name"),
                btn("✏️ Familiya", "edit_surname"),
                btn("✏️ Telefon", "edit_phone"),
                btn("✏️ Qo'shimcha telefon", "edit_extra_phone"),
                btn("✏️ Lokatsiya", "edit_location"),
                btn("✏️ Buyurtma miqdori", "edit_quantity"),
                btn("🏠 Asosiy menyu", "back_main")
        );
        send(chatId, info, markup, null);
    }

    private InlineKeyboardMarkup ratingKeyboard() {
        return inlineMarkup(
                btn("⭐ 1", "rate_1"),
                btn("⭐⭐ 2", "rate_2"),
                btn("⭐⭐⭐ 3", "rate_3"),
                btn("⭐⭐⭐⭐ 4", "rate_4"),
                btn("⭐⭐⭐⭐⭐ 5", "rate_5")
        );
    }

    private InlineKeyboardMarkup backToMainMenuKeyboard() {
        return inlineMarkup(btn("⬅️ Asosiy menyu", "back_main"));
    }

    private InlineKeyboardMarkup backToStartMenuKeyboard() {
        return inlineMarkup(btn("⬅️ Orqaga", "back_start"));
    }

    // ─── Inline keyboard builder helpers ──────────────────────────────────────
    private InlineKeyboardButton btn(String text, String data) {
        InlineKeyboardButton b = new InlineKeyboardButton();
        b.setText(text); b.setCallbackData(data);
        return b;
    }

    /** Each button gets its own row */
    private InlineKeyboardMarkup inlineMarkup(InlineKeyboardButton... buttons) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (InlineKeyboardButton b : buttons) rows.add(Collections.singletonList(b));
        InlineKeyboardMarkup m = new InlineKeyboardMarkup();
        m.setKeyboard(rows);
        return m;
    }

    // ─── Reply keyboards ──────────────────────────────────────────────────────
    private ReplyKeyboardMarkup phoneKeyboard() {
        KeyboardButton btn = new KeyboardButton("📱 Telefon raqamni ulashish");
        btn.setRequestContact(true);
        KeyboardRow row = new KeyboardRow(); row.add(btn);
        ReplyKeyboardMarkup m = new ReplyKeyboardMarkup();
        m.setKeyboard(Collections.singletonList(row));
        m.setResizeKeyboard(true); m.setOneTimeKeyboard(true);
        return m;
    }

    private ReplyKeyboardMarkup locationKeyboard() {
        KeyboardButton btn = new KeyboardButton("📍 Lokatsiyani ulashish");
        btn.setRequestLocation(true);
        KeyboardRow row = new KeyboardRow(); row.add(btn);
        ReplyKeyboardMarkup m = new ReplyKeyboardMarkup();
        m.setKeyboard(Collections.singletonList(row));
        m.setResizeKeyboard(true); m.setOneTimeKeyboard(true);
        return m;
    }

    // ─── Send helpers ─────────────────────────────────────────────────────────
    private void send(long chatId, String text, InlineKeyboardMarkup inline, ReplyKeyboardMarkup reply)
            throws TelegramApiException {
        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText(text);
        if (inline != null) msg.setReplyMarkup(inline);
        if (reply  != null) msg.setReplyMarkup(reply);
        execute(msg);
    }

    private void sendRemoveKeyboard(long chatId, String text) throws TelegramApiException {
        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText(text);
        ReplyKeyboardRemove r = new ReplyKeyboardRemove();
        r.setRemoveKeyboard(true);
        msg.setReplyMarkup(r);
        execute(msg);
    }

    private String nvl(String s) { return s == null || s.isBlank() ? "—" : s; }

    // ─── Admin guruhga xabar yuborish ─────────────────────────────────────────
    private void sendToAdminGroup(String text) {
        try {
            System.out.println("📤 Admin guruhga yuborilmoqda... ID: " + ADMIN_GROUP_ID);
            SendMessage msg = new SendMessage();
            msg.setChatId(String.valueOf(ADMIN_GROUP_ID));
            msg.setText(text);
            execute(msg);
            System.out.println("✅ Admin guruhga muvaffaqiyatli yuborildi!");
        } catch (TelegramApiException e) {
            System.err.println("⚠️ XATO: " + e.getMessage());
        }
    }
}
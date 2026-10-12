package ru.partsflow.publishing.drom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбор тела ответа Дрома по его виду (задача 0268).
 *
 * <p><b>Почему не подстрока.</b> Прежде и успех, и код отказа определялись
 * поиском {@code ERROR_REASON} в произвольном месте тела. Тогда страница
 * ошибки, в тексте которой встретилось это слово, читалась как отказ
 * площадки с кодом, а код, который Дром однажды напишет иначе
 * ({@code ERROR_REASON_PACKET_NOT_FOUND_V2}), — как известный: подстрока
 * короче совпадает с длинной. Здесь тело сначала узнаётся по виду (пусто,
 * JSON, веб-страница, иная разметка, простой текст), и код берётся
 * <b>только как отдельное значение</b>: строка JSON целиком, текст элемента
 * целиком, слово текста целиком. Со страницы сайта код не берётся вовсе —
 * это не ответ API.
 *
 * <p><b>Регистр значим.</b> Коды Дрома — константы, и
 * {@code error_reason_auth_failed} кодом не считается: это незнакомый ответ,
 * и человек увидит его как незнакомый, с самим текстом, а не догадку.
 *
 * <p>Формат ответа Дрома в живую не снимали (справка называет только коды),
 * поэтому разбор принимает все три правдоподобных обрамления, а не одно.
 */
public final class DromAnswer {

    /** Код целиком: слова через подчёркивание, только заглавные и цифры. */
    private static final Pattern CODE = Pattern.compile("ERROR_REASON(?:_[A-Z0-9]+)+");

    private static final Pattern HTML = Pattern.compile("(?is)<!doctype\\s+html|<html[\\s>]");
    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern ELEMENT_TEXT = Pattern.compile(">([^<]*)<");
    private static final Pattern WORD_SPLIT = Pattern.compile("[^A-Za-z0-9_]+");
    private static final Pattern TAGS = Pattern.compile("(?s)<[^>]*>");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Сколько знаков ответа показывать человеку: дальше — в «Ответ Дрома». */
    static final int PREVIEW = 200;

    /** Вид тела. */
    public enum Form {
        /** Пусто или одни пробелы. */
        EMPTY,
        /** Разобранный JSON. */
        JSON,
        /** Веб-страница — то, что отдаёт сайт или прокси, а не API. */
        PAGE,
        /** Иная разметка (XML). */
        MARKUP,
        /** Всё остальное. */
        TEXT
    }

    /**
     * @param form    вид тела
     * @param codes   коды {@code ERROR_REASON_*}, найденные отдельными
     *                значениями, без повторов, в порядке появления
     * @param mentionsReason встречается ли {@code error_reason} где-либо
     *                в любом регистре — признак того, что тело про отказ,
     *                даже если кода в нём не разобрать
     * @param preview короткий текст для человека: заголовок страницы, текст
     *                без разметки или само тело, не длиннее {@link #PREVIEW}
     */
    public record Parsed(Form form, List<String> codes, boolean mentionsReason, String preview) {
    }

    private DromAnswer() {
    }

    public static Parsed parse(String body) {
        String text = body == null ? "" : body.strip();
        if (text.startsWith("﻿")) {
            text = text.substring(1).strip();
        }
        boolean mentions = text.toLowerCase(Locale.ROOT).contains("error_reason");
        if (text.isEmpty()) {
            return new Parsed(Form.EMPTY, List.of(), false, "");
        }
        if (text.startsWith("{") || text.startsWith("[")) {
            JsonNode node = json(text);
            if (node != null) {
                Set<String> codes = new LinkedHashSet<>();
                collectJson(node, codes);
                return new Parsed(Form.JSON, List.copyOf(codes), mentions, preview(text));
            }
        }
        if (text.startsWith("<")) {
            if (HTML.matcher(text).find()) {
                // Страница сайта или прокси: код с неё не берётся — даже если
                // в тексте страницы он встречается, это не ответ API.
                Matcher title = TITLE.matcher(text);
                String shown = title.find() ? title.group(1) : TAGS.matcher(text).replaceAll(" ");
                return new Parsed(Form.PAGE, List.of(), mentions, preview(shown));
            }
            Set<String> codes = new LinkedHashSet<>();
            Matcher element = ELEMENT_TEXT.matcher(text);
            while (element.find()) {
                addIfCode(element.group(1).strip(), codes);
            }
            return new Parsed(Form.MARKUP, List.copyOf(codes), mentions,
                    preview(TAGS.matcher(text).replaceAll(" ")));
        }
        Set<String> codes = new LinkedHashSet<>();
        for (String word : WORD_SPLIT.split(text)) {
            addIfCode(word, codes);
        }
        return new Parsed(Form.TEXT, List.copyOf(codes), mentions, preview(text));
    }

    /**
     * Принят ли запрос: HTTP 200 и в теле нет ничего похожего на отказ.
     *
     * <p>Успешный ответ Дрома — «200 и ничего больше» (справка API); что
     * в нём бывает кроме пустоты, у нас не зафиксировано. Поэтому успех
     * определяется от противного, но <b>осторожно</b>: тело, где слово
     * {@code error_reason} есть хоть в каком виде, и веб-страница вместо
     * ответа API успехом не считаются — иначе незнакомый отказ молча
     * становился бы «принято», а страница входа сайта — подтверждением.
     */
    public static boolean accepted(int httpStatus, String body) {
        if (httpStatus != 200) {
            return false;
        }
        Parsed parsed = parse(body);
        return parsed.codes().isEmpty() && !parsed.mentionsReason() && parsed.form() != Form.PAGE;
    }

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    private static void collectJson(JsonNode node, Set<String> codes) {
        if (node.isTextual()) {
            addIfCode(node.textValue().strip(), codes);
        } else if (node.isContainerNode()) {
            List<JsonNode> children = new ArrayList<>();
            node.elements().forEachRemaining(children::add);
            for (JsonNode child : children) {
                collectJson(child, codes);
            }
        }
    }

    private static void addIfCode(String value, Set<String> codes) {
        if (CODE.matcher(value).matches()) {
            codes.add(value);
        }
    }

    private static String preview(String text) {
        String flat = SPACES.matcher(text).replaceAll(" ").strip();
        return flat.length() <= PREVIEW ? flat : flat.substring(0, PREVIEW) + "…";
    }
}

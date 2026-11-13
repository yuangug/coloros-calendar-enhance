package com.lcodexs.coursealarm;

/**
 * 内嵌的 xlsx 模板（openpyxl 生成）：两张表 —— 「课表」网格版式照抄教务系统导出的 xls（
 * 标题 / 星期表头 / 五个大节 / 备注行），「课程明细」纯表格（同时用于回灌解析）。
 * 格子里的 @@R{r}C{c}@@ / @@TITLE@@ / @@NOTE@@ 是占位符，导出时替换成文字。
 *
 * 为什么不放 assets：LSPosed 模块的代码跑在宿主进程里，Context.getAssets() 拿到的是
 * **宿主**的 assets（com.coloros.calendar），模块自己的读不到。所以 base64 编进代码。
 */
final class TemplateXlsx {
    private TemplateXlsx() {
    }

    private static final String B64 =
            "UEsDBBQAAAAIAOCibV1Gx01IlQAAAM0AAAAQAAAAZG9jUHJvcHMvYXBwLnhtbE3PTQvCMAwG4L9SdreZih6kDkQ9ip68zy51hbYpbYT67+0EP255ecgboi6J" +
            "Iia2mEXxLuRtMzLHDUDWI/o+y8qhiqHke64x3YGMsRoPpB8eA8OibdeAhTEMOMzit7Dp1C5GZ3XPlkJ3sjpRJsPiWDQ6sScfq9wcChDneiU+ixNLOZcrBf+L" +
            "U8sVU57mym/8ZAW/B7oXUEsDBBQAAAAIAOCibV1f1hVG7QAAACsCAAARAAAAZG9jUHJvcHMvY29yZS54bWzNks9qwzAMh19l+J4odqAHk/qystMGgxU2djO2" +
            "2prFf7A1kr79kqxNGdsD7Gjp50+fQJ1J0sSMzzkmzOSw3I2+D0WatGUnoiQBijmh16WeEmFqHmL2mqZnPkLS5kMfEUTTbMAjaatJwwys0kpkqrNGmoyaYr7g" +
            "rVnx6TP3C8wawB49BirAaw5MzRPTeew7uAFmGGH25buAdiUu1T+xSwfYJTkWt6aGYaiHdslNO3B4e3p8WdatXCikg8HpV3GSzgm37Dr5tb3f7R+YEo3YVJxX" +
            "vN1zIUUrG/4+u/7wuwn7aN3B/WPjq6Dq4NddqC9QSwMEFAAAAAgA4KJtXZlcnCMQBgAAnCcAABMAAAB4bC90aGVtZS90aGVtZTEueG1s7Vpbc9o4FH7vr9B4" +
            "Z/ZtC8Y2gba0E3Npdtu0mYTtTh+FEViNbHlkkYR/v0c2EMuWDe2STbqbPAQs6fvORUfn6Dh58+4uYuiGiJTyeGDZL9vWu7cv3uBXMiQRQTAZp6/wwAqlTF61" +
            "WmkAwzh9yRMSw9yCiwhLeBTL1lzgWxovI9bqtNvdVoRpbKEYR2RgfV4saEDQVFFab18gtOUfM/gVy1SNZaMBE1dBJrmItPL5bMX82t4+Zc/pOh0ygW4wG1gg" +
            "f85vp+ROWojhVMLEwGpnP1Zrx9HSSICCyX2UBbpJ9qPTFQgyDTs6nVjOdnz2xO2fjMradDRtGuDj8Xg4tsvSi3AcBOBRu57CnfRsv6RBCbSjadBk2PbarpGm" +
            "qo1TT9P3fd/rm2icCo1bT9Nrd93TjonGrdB4Db7xT4fDronGq9B062kmJ/2ua6TpFmhCRuPrehIVteVA0yAAWHB21szSA5ZeKfp1lBrZHbvdQVzwWO45iRH+" +
            "xsUE1mnSGZY0RnKdkAUOADfE0UxQfK9BtorgwpLSXJDWzym1UBoImsiB9UeCIcXcr/31l7vJpDN6nX06zmuUf2mrAaftu5vPk/xz6OSfp5PXTULOcLwsCfH7" +
            "I1thhyduOxNyOhxnQnzP9vaRpSUyz+/5CutOPGcfVpawXc/P5J6MciO73fZYffZPR24j16nAsyLXlEYkRZ/ILbrkETi1SQ0yEz8InYaYalAcAqQJMZahhvi0" +
            "xqwR4BN9t74IyN+NiPerb5o9V6FYSdqE+BBGGuKcc+Zz0Wz7B6VG0fZVvNyjl1gVAZcY3zSqNSzF1niVwPGtnDwdExLNlAsGQYaXJCYSqTl+TUgT/iul2v6c" +
            "00DwlC8k+kqRj2mzI6d0Js3oMxrBRq8bdYdo0jx6/gX5nDUKHJEbHQJnG7NGIYRpu/AerySOmq3CEStCPmIZNhpytRaBtnGphGBaEsbReE7StBH8Waw1kz5g" +
            "yOzNkXXO1pEOEZJeN0I+Ys6LkBG/HoY4SprtonFYBP2eXsNJweiCy2b9uH6G1TNsLI73R9QXSuQPJqc/6TI0B6OaWQm9hFZqn6qHND6oHjIKBfG5Hj7lengK" +
            "N5bGvFCugnsB/9HaN8Kr+ILAOX8ufc+l77n0PaHStzcjfWfB04tb3kZuW8T7rjHa1zQuKGNXcs3Ix1SvkynYOZ/A7P1oPp7x7frZJISvmlktIxaQS4GzQSS4" +
            "/IvK8CrECehkWyUJy1TTZTeKEp5CG27pU/VKldflr7kouDxb5OmvoXQ+LM/5PF/ntM0LM0O3ckvqtpS+tSY4SvSxzHBOHssMO2c8kh22d6AdNfv2XXbkI6Uw" +
            "U5dDuBpCvgNtup3cOjiemJG5CtNSkG/D+enFeBriOdkEuX2YV23n2NHR++fBUbCj7zyWHceI8qIh7qGGmM/DQ4d5e1+YZ5XGUDQUbWysJCxGt2C41/EsFOBk" +
            "YC2gB4OvUQLyUlVgMVvGAyuQonxMjEXocOeXXF/j0ZLj26ZltW6vKXcZbSJSOcJpmBNnq8reZbHBVR3PVVvysL5qPbQVTs/+Wa3InwwRThYLEkhjlBemSqLz" +
            "GVO+5ytJxFU4v0UzthKXGLzj5sdxTlO4Ena2DwIyubs5qXplMWem8t8tDAksW4hZEuJNXe3V55ucrnoidvqXd8Fg8v1wyUcP5TvnX/RdQ65+9t3j+m6TO0hM" +
            "nHnFEQF0RQIjlRwGFhcy5FDukpAGEwHNlMlE8AKCZKYcgJj6C73yDLkpFc6tPjl/RSyDhk5e0iUSFIqwDAUhF3Lj7++TaneM1/osgW2EVDJk1RfKQ4nBPTNy" +
            "Q9hUJfOu2iYLhdviVM27Gr4mYEvDem6dLSf/217UPbQXPUbzo5ngHrOHc5t6uMJFrP9Y1h75Mt85cNs63gNe5hMsQ6R+wX2KioARq2K+uq9P+SWcO7R78YEg" +
            "m/zW26T23eAMfNSrWqVkKxE/Swd8H5IGY4xb9DRfjxRiraaxrcbaMQx5gFjzDKFmON+HRZoaM9WLrDmNCm9B1UDlP9vUDWj2DTQckQVeMZm2NqPkTgo83P7v" +
            "DbDCxI7h7Yu/AVBLAwQUAAAACADgom1d1vhg01UDAACWEQAAGAAAAHhsL3dvcmtzaGVldHMvc2hlZXQxLnhtbJ2YW2/aMBiG7/crolTqXRc751BAjJ6otEPV" +
            "dtt1CgaiJjFLzOj+/WznUFrlIx+9aIMdP69t/MQoHu548VyuGRPGS5bm5chcC7EZWFY5X7MsLj/zDcvlnSUvsljIYrGyyk3B4oWGstSyCfGtLE5yczzUdXfF" +
            "eMi3Ik1ydlcY5TbL4uLflKV8NzKp2VTcJ6u1UBXWeLiJV+yBiZ+bu0KWrDZlkWQsLxOeGwVbjswvdDCLVHvd4FfCduXeZ0PN5InzZ1W4XYxMogbEUjYXKiGW" +
            "l7/sgqWpCpLD+FNnmm2XCtz/3KRf67nLuTzFJbvg6e9kIdYjMzSNBVvG21Tc892M1fPxVN6cp6X+b+yqtlT2ON+Wgmc1LMtZklfX+KX+HvYA2wUAuwZsLODU" +
            "gIMF3BpwsYBXAx4W8GvAxwJBDQRYIKyBUK9utRx6LS9jEY+HBd8ZhW6t1swmTUq7ilKduWqhTdENZW2SK6cfRCHvJjJQjCeTx9vHr1eTydASsh9Vac3ln8xv" +
            "O7GrTvyuPt40dPpH4+jR2MBoTk8cx6Xk/PTEDlzbO387KJ0w7Uuwfeo6KsF3okheaRT5YVfSxbFJNqGkM+nyA2MKgq6kq6PHZNtO5/d0/YHZUb8r6eb4pNDt" +
            "HNPs6CSfhO+T3hjnVsZFnW5Wxrl9xlHPsVtPmlVuTPxEwgElZyQaeKTLxirdAZ+ue/fCfv9wVfYhSKeTvESQbid5hSC9TvIaQfqd5A2CDDrJGYIMD25cXr8d" +
            "Ht4O+cQ2z1trByXKDkoBO7y+GXiQHQgSsANBAnYgSMAOBAnYgSABOxDkYTv8fjt8pB3V3tnsfK92uNoOD7DD75uBD9mBIAE7ECRgB4IE7ECQgB0IErADQR62" +
            "I+i3Izhi76C1Hc6eHb62IwDsCPpmEEB2IEjADgQJ2IEgATsQJGAHggTsQJCH7Qj77QjRewfxtR3Uoft2RANCzmw6cLwuO8K+GYSQHQgSsANBAnYgSMAOBAnY" +
            "gSABOxDkYTuiyg4agnZMI92HC/bx/ccj8FJm7b0FZqxY6ROB0pjzbV730NZWhw7TqD50sF6bVycW3+JileSlkbKlRMnnQP6mFtVIq4LgG/3q+MSFnEX1usni" +
            "BStUA3l/ybloCqqD9ihm/B9QSwMEFAAAAAgA4KJtXWlGz8oMAgAAuwUAABgAAAB4bC93b3Jrc2hlZXRzL3NoZWV0Mi54bWyNlF1v2yAUhv+K5Um7rG3AX61j" +
            "aU3XZReTolZbr4lzkqBi4wGZu38/wCSKqqz2jfGB8z4+cF5TDUK+qgOADt5a3qlFeNC6v40i1RygpepG9NCZlZ2QLdUmlPtI9RLo1olaHqE4zqKWsi6sKze3" +
            "lnUljpqzDtYyUMe2pfLvPXAxLMIkPE08sf1B24mornq6h2fQP/u1NFF0pmxZC51iogsk7Bbhl+R25fJdwi8Gg7p4D+xONkK82uD7dhHGtiDg0GhLoGb4A0vg" +
            "3IJMGb89Mzx/0gov30/0R7d3s5cNVbAU/IVt9WERFmGwhR09cv0khhX4/aSW1wiu3DMYxlwch0FzVFq0XmwqaFk3jvTNn8OFIEH/ESAvQHMF2AvwXAHxAjJX" +
            "kHpBOleQeUE2V5B7QT5XUHhB4bo7tsP18oFqWldSDIF02bZn6NyccxeNdRqb4ZziDt3Mss56+llLs8oMUNefP+G0wMWdGRNEsBlRkib5GCfZXRVp83mbGzWe" +
            "eD9FRGlJUksicY6uEZaTBIRRbAlFkebXCA+ThCwZd5PhsrxG+DpJIL4GVBbuPDBJ4mukx8kTRiTLXS0kwx+Rvk3WlGSx7RXKCUqvEVaTtZRx5rqNcUneESJj" +
            "qtPtMbrMXms/qNyzTgUcdoYZ3+TmZ5GjycZAi94ZcSO0MeDoSXO7grQJZn0nhD4F1svn+7r+B1BLAwQUAAAACADgom1dcDQnljIDAADHDgAADQAAAHhsL3N0" +
            "eWxlcy54bWzdV+Fu0zAQfpXID0CahkUNaipBWSUkQJO2H/x1G6e15MTBcUe7/zwAv3kHeAAEbzOJvQU+O02zzjd1Q+wHiarYd/fdfT7f2eq40VvBzleM6WBT" +
            "iqrJyErr+kUYNosVK2nzTNasMppCqpJqM1XLsKkVo3kDoFKEw8EgCUvKKzIZV+tyVuomWMh1pTMyIOFkXMhqLzkhTmBMacmCSyoyMqWCzxW3trTkYuvEQxAs" +
            "pJAq0IYKy0gEkubKqSM3A5atn5JXUoEwdBEO41z/+vb75/ebr59vfnwBs3nr6K7Xv3EweICDHSh9BGbUw9hPY7BciC7VMXGCybimWjNVzczEYqzwjipoxxfb" +
            "2uR6qeg2Gp6QowGNFDyHkMup3TO1nGdkNnt9ehqdJtZND9o5tR/DfC5VzlTHfUh2oslYsEIbuOLLFXy1rCH1UmtZmkHO6VJW1C5sh+gjA1vgGdErW6CLPrfR" +
            "AF7LDUzbGEcirK2lcyTAWO54H4lwxr2FtQOTrwUT4hycfCi6pEXG1aYIXA++yaH9AiiM3dBkuh06N24CgfrenO+e25NHuQ1qfin1q7VZQWXnH9dSszPFCr6x" +
            "803Rxce8R7h3Wtdi+1LwZVUyt/ajA07GdIcLVlLxKxMNGmphBEyR4JIpzRc9CWRoU+A0h3uawz7N6GlpBp8UrS/YRrcn0r2cYyS1/4YzdFefsWmFh9F9/qSV" +
            "cDTdsG2VXj/e6sZOGsB5npH3cImKPYFgvuZC86qdrXies+pOUxr3ms7NLX3Lv7HPWUHXQl90yozsx+9Yztdl2lmdQVJaq/34LZxiUdJdtSYWr3K2Yfm0nZpj" +
            "qXegD9oHAIeamX38GgzjdH4N6LA4GAMM41BYnP9pPSN0PU6HcRt5NSMUM0Ixg+4GO9RM7YvF8WNS8/hXmqZxnCRYRqdTL4MplrckgZ/fG8YNEFgciPSwXOO7" +
            "jVfI/XWA7el9FYKtFK9EbKV4rkHjzxsg0tS/21gcQGC7gNUOxPfHgZryY+IYdhXjhnUwrklTTAO16K/RJEGyk8Dr3x+sS+I4Tf0a0PkZxDGmgW7ENRgD4IBp" +
            "4tjegwf3Ubi7p8L9X9fJH1BLAwQUAAAACADgom1dl4q7HMAAAAATAgAACwAAAF9yZWxzLy5yZWxznZK5bsMwDEB/xdCeMAfQIYgzZfEWBPkBVqIP2BIFikWd" +
            "v6/apXGQCxl5PTwS3B5pQO04pLaLqRj9EFJpWtW4AUi2JY9pzpFCrtQsHjWH0kBE22NDsFosPkAuGWa3vWQWp3OkV4hc152lPdsvT0FvgK86THFCaUhLMw7w" +
            "zdJ/MvfzDDVF5UojlVsaeNPl/nbgSdGhIlgWmkXJ06IdpX8dx/aQ0+mvYyK0elvo+XFoVAqO3GMljHFitP41gskP7H4AUEsDBBQAAAAIAOCibV31gu6zXgEA" +
            "ALUCAAAPAAAAeGwvd29ya2Jvb2sueG1stVJBTsMwEPxK5AeQtIJKVE0vVEAlBBVFvTvJplnV9ka200IfwAWJM1duvfEAJJ5D+wwcRxGRkBAXTuudXY1nxh5t" +
            "SK8SolVwL4UyMSusLYdhaNICJDdHVIJyk5y05Na1ehmaUgPPTAFgpQj7UTQIJUfFxqOWa6bDbkMWUoukHFgDC4SN+Z7XbbBGgwkKtA8x82cBLJCoUOIWsphF" +
            "LDAFbS5J45aU5WKeahIiZr1msABtMf0Bz2uRdzwxHrE8ueVOSMwGkSPMURvrNzw/dxrX4JabrrJ0jsKCnnALF5qqEtWypnEuwo4Nn0NbmxCH+i8xUp5jChNK" +
            "KwnKNjlqELVAZQosDQsUlxCzw9vH4XVXG3I3TLPGnHWqOlHpIbqBnmZe379q2e+ePl+e9++PHUX9XxT1fWJtTBnkqCC7dmzG4e7J0pkO6uKd9Y9PeqfuaSoh" +
            "zhx2o66IZ23q7Y8ZfwFQSwMEFAAAAAgA4KJtXY33LFq0AAAAiQIAABoAAAB4bC9fcmVscy93b3JrYm9vay54bWwucmVsc8WSTQqDMBBGrxJygI7a0kVRV924" +
            "LV4g6PiD0YTMlOrta3WhgS66ka7CNyHvezCJH6gVt2agprUkxl4PlMiG2d4AqGiwV3QyFof5pjKuVzxHV4NVRadqhCgIruD2DJnGe6bIJ4u/EE1VtQXeTfHs" +
            "ceAvYHgZ11GDyFLkytXIiYRRb2OC5QhPM1mKrEyky8pQwr+FIk8oOlCIeNJIm82avfrzgfU8v8WtfYnr0N/J5eMA3s9L31BLAwQUAAAACADgom1dbqckvB4B" +
            "AABXBAAAEwAAAFtDb250ZW50X1R5cGVzXS54bWzFlM9OwzAMxl+lynVqMnbggNZdgCvswAuE1l2j5p9ib3Rvj9tuk0CjYioSl0aN7e/n+IuyfjtGwKxz1mMh" +
            "GqL4oBSWDTiNMkTwHKlDcpr4N+1U1GWrd6BWy+W9KoMn8JRTryE26yeo9d5S9tzxNprgC5HAosgex8SeVQgdozWlJo6rg6++UfITQXLlkIONibjgBKGuEvrI" +
            "z4BT3esBUjIVZFud6EU7zlKdVUhHCyinJa70GOralFCFcu+4RGJMoCtsAMhZOYoupsnEE4bxezebP8hMATlzm0JEdizB7bizJX11HlkIEpnpI16ILD37fNC7" +
            "XUH1SzaP9yOkdvAD1bDMn/FXjy/6N/ax+sc+3kNo//qq96t02vgzXw3vyeYTUEsBAhQAFAAAAAgA4KJtXUbHTUiVAAAAzQAAABAAAAAAAAAAAAAAAIABAAAA" +
            "AGRvY1Byb3BzL2FwcC54bWxQSwECFAAUAAAACADgom1dX9YVRu0AAAArAgAAEQAAAAAAAAAAAAAAgAHDAAAAZG9jUHJvcHMvY29yZS54bWxQSwECFAAUAAAA" +
            "CADgom1dmVycIxAGAACcJwAAEwAAAAAAAAAAAAAAgAHfAQAAeGwvdGhlbWUvdGhlbWUxLnhtbFBLAQIUABQAAAAIAOCibV3W+GDTVQMAAJYRAAAYAAAAAAAA" +
            "AAAAAAC2gSAIAAB4bC93b3Jrc2hlZXRzL3NoZWV0MS54bWxQSwECFAAUAAAACADgom1daUbPygwCAAC7BQAAGAAAAAAAAAAAAAAAtoGrCwAAeGwvd29ya3No" +
            "ZWV0cy9zaGVldDIueG1sUEsBAhQAFAAAAAgA4KJtXXA0J5YyAwAAxw4AAA0AAAAAAAAAAAAAAIAB7Q0AAHhsL3N0eWxlcy54bWxQSwECFAAUAAAACADgom1d" +
            "l4q7HMAAAAATAgAACwAAAAAAAAAAAAAAgAFKEQAAX3JlbHMvLnJlbHNQSwECFAAUAAAACADgom1d9YLus14BAAC1AgAADwAAAAAAAAAAAAAAgAEzEgAAeGwv" +
            "d29ya2Jvb2sueG1sUEsBAhQAFAAAAAgA4KJtXY33LFq0AAAAiQIAABoAAAAAAAAAAAAAAIABvhMAAHhsL19yZWxzL3dvcmtib29rLnhtbC5yZWxzUEsBAhQA" +
            "FAAAAAgA4KJtXW6nJLweAQAAVwQAABMAAAAAAAAAAAAAAIABqhQAAFtDb250ZW50X1R5cGVzXS54bWxQSwUGAAAAAAoACgCEAgAA+RUAAAAA";

    static byte[] bytes() {
        try {
            return android.util.Base64.decode(B64, android.util.Base64.DEFAULT);
        } catch (Throwable t) {
            return new byte[0];
        }
    }
}
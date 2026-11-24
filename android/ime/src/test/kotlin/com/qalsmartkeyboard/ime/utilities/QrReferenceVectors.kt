package com.qalsmartkeyboard.ime.utilities

/**
 * Reference QR matrices (level M, byte mode, border 0) for every mask, generated once with the Python `qrcode` 8.2 package
 * (`QRCode(error_correction=ERROR_CORRECT_M, mask_pattern=m)`, `add_data(.., optimize=0)`). Each matrix is row-major, 4 modules per
 * hex digit, dark = 1. The encoder test compares its own output module for module.
 */
internal object QrReferenceVectors {
    class Vector(val text: String, val version: Int, val size: Int, val maskHex: List<String>)

    val all: List<Vector> = listOf(
        Vector(
            "https://example.com",
            2, 25,
            listOf(
                "fe7f3fc15dd06e8c6bb74365dba872ec107907faaafe003d00aa6b8944d410708906e3353824af8e59ede726ef6acea81f12" +
                    "9e13fc0069c6ff996b704a119ba88fcdd0e8f2ea6a23041d5afeb3f18",
                "feaa3fc137506eb92bb749c5dba522ec16d107faaafe009700a33e92ee7ebae5dc53a99f9281fadb0b474d8fba3f9a02b5b8" +
                    "cb46f90043447fac2a3040b13ba5df9dd2425aeb3f7704b7f0fee6a48",
                "fe1cbfc113d06eb48bb750e5dbae4aec149907faaafe010500be083e265a28beb1e57b96b61721b6bfc504aa0ce4f72227f1" +
                    "a6f0fa0067c63f81aaf05991abaebfadd40b7eebe41b0425b9fed07f8",
                "fe9cbfc17e506e8febb750e5dba3faec13f507faaafe01b300b765a5a65a2893873e00bb00c721b6ba63dfc569522d2227f1" +
                    "122bff8051457fa1aaf052f11ba06fc5d40b7eea89ad04fed4fed07f8",
                "fedbbfc130506e9aabb757f5dbaa3aec16a107faaafe0174008bcf7c9eb9a69dbfdd8a11aa603dc77c4b3c483c07783a5636" +
                    "d737fe0044443fafaa104e91dbaacfedd2339ee9079504547efe97638",
                "fe2a3fc173506eb48bb75c95dba64aec129107faaafe0187008288673a2befbeb1e5799e9691fadb0f470c8a0ce4f753e0ed" +
                    "a6f0fa0047443f8c2a3041b12ba6bfadd3170ee9e41b04a7b1fee6a48",
                "feaa3fc170506ebdabb74c95dbaf6aec131507faaafe008b009faccbba2befb7957738188ef1fadb0c4b3c4b2876bf53e0ed" +
                    "8262fb005f45bfac2a3051712bacff8dd7170ee9ad3f04c637fee6a48",
                "fe7f3fc10f906e88ebb74365dba23aec14e907faaafe00740096f9d044d41062c02267a77104af8e5ba4c3b67d23eaa81f12" +
                    "d737fe0060c67f996b705e91dba1afddd4e8f2e8f86b0439c8feb3f18",
            ),
        ),
        Vector(
            "the quick brown fox jumps over the lazy dog, ok",
            4, 33,
            listOf(
                "fe61113fc14aead06e9006cbb749a845dbac003aec10831907faaaaafe0069df00aa17dd8922b04473d4ef3326f50e12c32b" +
                    "ec004b0600c8830e7ac0109f316ee97a665a4e61c6287070f8f18baff3989ec8a16f5baf1a421fe7263b26e86ef03199618a" +
                    "b7d5f9006154c67f9f316af04f61112ba8810fcdd09aaa5eea51926b0426eb02fef2eda98",
                "feb4443fc12040506ea5538bb74302e5dba1556aec1629b107faaaaafe00c37500a3428892881aeed941ba6673bfa4b8698e" +
                    "b9551e54aa6229a72f9545cb9bc443d0330f1b34ec82dada6da4defab9323462043a0efa48e8b54d8f6e73bd3a5a9b33cbdf" +
                    "e280fc004bfe44ffaa642bb045cbb18ba5d45f9dd23000f6eb04c73f048c41a8fea7b8fc8",
                "fe029f3fc104d2106ea8e54bb75a2675dbaa38daec14609507faaaaafe01513c00be7453be403e7c905ad7d0a8cdad9cfbc8" +
                    "6238a888282b0d36994e287cbb560af45eb9c05924a6489376c96821cb3b10f042e1634c946afc691ed8a8d08d7a097aefb2" +
                    "545bf9806f6c45ff87d2ead05cef31cbaeb9efc5d4792466ebdfaa89041e088cfe9163910",
                "fe829f3fc16964d06e9388ebb75a2675dba78e02ec130d2107faaaaafe01e7e700b719e525c03e7c9077e10bc576802a20a8" +
                    "6238a88d8ef06081fcf8f310bb560af4ea62adefc81093fef6c96821dda07d469f77b821246afc691c0bc56656c8d21759b2" +
                    "545bf98042dac6bfbcbf6a105cef31cba30f3fadd71492beebdfaa8904a8d3e1fefcd54a0",
                "fec5833fc1275c106e86ddabb75d3a05dbae491aec16587507faaaaafe0120fb008bb34ffcf8ddf2a8b9d9e84b5c2a808a0f" +
                    "7e496f93a613eeb8a9ada645a327cde82f7edc289c45c6ab95c750c25abc0c8185fd128b8fe4c48a90e84b5eb46278bdf3c3" +
                    "9347f8004ce2453fa9ea2b504bf3510baac82fddd241c7eae93c24b1046fcf90fed67fe08",
                "fe34443fc16450106ea8e54bb75657b5dba238daec1268b507faaaaafe01d3340082f453e75c4fbb8c1ad7d0a8cfa5bc79ce" +
                    "b9551e50aa232db6994e287cca9116855eb9c0592c86ca9b6da4defaa933307242e1634c95ade018dad8a8d08d5a8b72cfdf" +
                    "e280fc004fee45ff87d2ead0409ef1dba6b9efc5d27104e6e904c73f049c00acfe9163910",
                "feb4443fc1675c106ea1c1cbb74657b5dbab1c4aec13eead07faaaaafe00df04009fd0c1cbdc4fbb8c13f342e1ee23a4184e" +
                    "b9551e53a613eebbbddc6158ca9116857a2b897daa9eab1d6da4defaa503f37e76732a6805ade018da4ae1f41f42eaf4d7df" +
                    "e280fc004ce2453faef66a90509ef1dbaf9d7f8dd7f71c86e904c73f0490306ffeb5f1d80",
                "fe61113fc118a3d06e94948bb749a845dba6491aec14115107faaaaafe0020fb00968594d022b04473c6a617b4b19c5be7bb" +
                    "ec004b0449ec1146e889340d316ee97a2f7edc28d46154e2b8f18baffabc0c8183267f3d52421fe7271fb4a14ab9150b288a" +
                    "b7d5f900731dc6ff9ba32bd05f61112ba2c82fddd408e37ae851926b046fcf90fee0a48d0",
            ),
        ),
        Vector(
            "the quick brown fox jumps over the lazy dog. the quick brown fox jumps over the lazy dog. pack my box with five doz",
            7, 45,
            listOf(
                "fe539b6f0bfc15466372906e81efac84bb748e3e8ab5dba995ffefaec112ec79e107faaaaaaaafe00ec31bbf00aa32bfaac0" +
                    "95c364088e80fe9f5ac1252e6378133ba728714e16aa29a2cc96c0c81dfb771e64616a88beab5b11935ca6f9a7c9ccfb6ca4" +
                    "0fcfd7ed324417ba4d8913abb2fe87fbbbf9a44c34488475eb116a276af91c0d1d1b12ff895f9bbf8f255e7c4063799cd0d4" +
                    "65af83eadcf31e17221b7f8af0829a794c483df87c92661afb82326fb5e86a4e6e0bcb0b466d3c0c02c2c9411637eef0735e" +
                    "7d1529a172fcbcf88055fc40044ff8736b602bb0408d13fb1bbab4cffb9f8dd2e0d0ccdc6ea12a8677ff042b7b9f72afecb1" +
                    "bdfcc98",
                "fe86ce3a4bfc13ecc9d8906eb4baf9d4bb7424942035dba4c0fabbaec17844534107faaaaaaaafe00469111500a367efff91" +
                    "2f69cea2242a6bca0f94707ac9d2b9910d8d241b43ff7c88663c6a62b4ae224b313420221401f1bbc609f3acf29b6651c60e" +
                    "a55a82b867114310e723b90117fbd2feeefc8c669c622c5caa442b722bb316a717b118afdc0fceefdd8ff4d6eac9ecc98581" +
                    "30fb29407659b4b2774e2adfa5a830d3e6e294ad29c7334fb12898c51f423f1b3b5e9e59ecc796a6a8429c144362baf2d9f4" +
                    "d7bf89b427f9e9fd807f546aac67fb262a356af04a27195111ba619faecfddd04a7a6676eeb47fd322ab0481d135d80fe9e4" +
                    "e8a99c8",
                "fe301557cbfc11a5ed4a906eb90c22b4bb75b6dd04b5dbafadfc63aec15cd45a6107faaaaaaaafe01d4d135c00be513f9223" +
                    "e3eb8786b66370a7b94f1dcde940f0b59fcbff76f524114042ae23462518f92687ef522b3093b89fabbf28c14447e418e29c" +
                    "ec41ef0ebc7cf430756a9d9351f8bff835f944420c6b0c4d2a9f6ac4eac11f8315f91ccfeadfa35f810dbdf27880f7a4335a" +
                    "5d4c09d23f7d26f4ac239c04c8601441afc6051bf2aa8594c321bc57566652ade03328856e8eb234e142f1a2980f0cf24bbd" +
                    "f32dc9af4aff32f8005bc4638c77f9fd6b83ab9053031b1915bad74fc37f85d6035ef43feeb9c9084f1d041398114a4fea89" +
                    "5e72f10",
                "feb01557cbfc177e80fc906e82619464bb75b6dd04b5dba21bff0faec12a0c77c107faaaaaaaafe01621158700b73c8fc94a" +
                    "5beb8786b6635d916222ab165b9b9d0344abff76f524111a2f18f82b927d4ffdea59922b3093b89f1f6445779f292f755447" +
                    "81c1ef0ebc7cf559c3b1f0258cfe64fd83fa44420c6b0c4febf2ea1fab7b12351e951acfeadfa35f84ab669fce5b81ff5eec" +
                    "862009d23f7d26ffe19547697e8ca29ac270dd1bf2aa8594d5bad1e18d0b8bc056e845356e8eb234e142c779f5b9d6f090d0" +
                    "45f6a9af4aff32f800767478e443fa4baaee2b5053031b1915ba0c2ff5afedd56ee82f526eb9c9084f1d04a5437cfc9fec52" +
                    "33c42a0",
                "fef709260bfc139d0ec4906e9734c134bb75c71a18b5dbabdcfb7faec17f5c628107faaaaaaaafe01a51129b008b962fe3e7" +
                    "c865bf65385b93a981ac93f4f13137a9ee0ce307323860f8a1201ba5ab281aa8bf0cc3ac2ce27f83da7834b0835c6a200112" +
                    "d4a2e1365ff2cd2804ad81e296fcceff29f8fc618453ec432a7ceafc2b51189f143f10bfadcfd29f9a838511f6b814aa0bb9" +
                    "d37511a3f8615733b0525b18b9d8f7cf97258b2b1124bd7752a6a026917a236afc42ef9ee0b651bad982ff9a7b8134f23a7a" +
                    "ef5c09b33bf82ef980784c5b6c7bfb1eebbb6a10441f1adf19ba905fb2bf9dd03bbd7a072e97f1ebc12504625f0d3b8feef8" +
                    "996e808",
                "fe06ce3a4bfc17adcdc8906eb90c22b4bb7571c17535dba7adfc63aec13c54524107faaaaaaaafe0156d11540082d13f9226" +
                    "722c9bf7717f30a7b94f1dcdc9c2f8951dcd241b43ff7cc8622c2b66a518f92687ef55374154a4eeabbf28c144476610c21e" +
                    "e45a82b867114210f762bd1151f8bff835f95c73cc777c492a9f6ac4eac317a317f11cafdc0fceefd98fb5d2fa88f7a4335a" +
                    "5d4c7815230ce1e4ac239c04c86834c3a7e684ad29c7334fa1299cd55e4652ade0332884a992c3f3fd02f1a2980f0cf2c9b5" +
                    "d3afc9b427f9e9fd807b446bac77f9fd6b83ab904f731c0514ba574fc37f85d00b7e7637ee947fd322ab04919031c84fea89" +
                    "5e72f10",
                "fe86ce3a4bfc179d0ec4906eb028b0f4bb7471c17535dbae89fe2baec12434544107faaaaaaaafe005af1d64009ff5afdb04" +
                    "ba2c9bf7717f39832b06395fd1a37e8d7c4d241b43ff7cf8a1201ba5a83c6b6fa37d15374154a4ee8f2d61e5d60f0796da7f" +
                    "625a82b8671142d3fb527e1d65faf6fca7f85c73cc777c49aad66a56abe311bb167714afdc0fceefda838511f6b825ed17c8" +
                    "14687815230ce1e6e5070e4decee2ca221fee4ad29c7334fad195fd96e851b89727a0c14a992c3f3fd02d530d12b9ef2a833" +
                    "cbce49b427f9e9fd80784c5b6c7bfb6f2aa72bd05f731c0514bac50fe7efcdd58d6617b1ee947fd322ab049da0f2c47fe8c0" +
                    "7ae0b80",
                "fe539b6f0bfc1062f13a906e857de5a4bb748e3e8ab5dba3dcfb7faec15bcc6ba107faaaaaaaafe00a51129b0096a0ff8e55" +
                    "05c364088e80ecd67e536c0a2a5c817283b8714e16aa29865edfe45a55693e3af6284a88beab5b11da7834b08358e8692580" +
                    "9d8fd7ed3244172804ad81e290ffa3f9f2fd244c34488474eb832b03eabd1e4519891bff895f9bbf8d6c7aee0947f0b8429d" +
                    "413d83eadcf31e13b0525b18b990d35dde0119f87c92661af2a6a026917a4edc272f5943466d3c0c02c28065847ecaf157cc" +
                    "3431b9a172fcbcf88047b4649447f83a6bf26a90508d13fb1bba105fb2bf9dd67299e84e2e812a8677ff04625f0d3b8fed95" +
                    "2fb5ed0",
            ),
        ),
    )
}

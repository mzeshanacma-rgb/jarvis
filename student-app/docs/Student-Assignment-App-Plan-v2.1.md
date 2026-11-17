# Student Assignment App: International Project Plan v2.1

2026-11-17 · Muhammad Zeshan

## v2.1 mein kya theek hua

v2 ke 14 safhon ki jaanch mein jo takrao mile, woh is version mein hal hain. Har tabdeeli ke saath app ka code bhi bana diya gaya hai (Phase 1, 47 tests aur browser ka poora loop pass).

| Masla (v2) | v2.1 mein hal |
|---|---|
| Security table ki Phase 1 cheezein (do-step login, encryption, tenant isolation test, hadse ka plan) "Phase 1 baqi" list mein nahi thin | Sab ban gayin: do-step login, files AES-256-GCM se encrypted, isolation ka automatic test, `docs/INCIDENT-PLAN.md` |
| AI queue memory mein; restart par drafts kho sakte the | Queue database mein, 5 dafa retry; Gate 2 ki shart |
| Gate 1 mein sirf ek number | Gate 1 = quality table ki teeno hadd + wakeel ki rai + Anthropic ki shartein + kharcha maloom; app ka Quality page yahi dikhata hai |
| Free version mein "teacher apni API key lagaye" bachchon ke liye khatarnak | Yeh option sirf 18+ ke liye; school ke bachchon ke liye sirf mahana fixed drafts |
| "Aap ke faislay jo baqi hain" do jagah, alag fehristein | Ek hi fehrist, aakhir mein |
| Free version "tay nahi" vs pricing table mein tay | Pricing table hi faisla hai: school ka 60 din trial, free teacher plan sirf 18+ |
| Offline: Phase 3 vs Phase 1 ka device par save | Phase 1 = app shell offline + jawab ki qatar; Phase 3 = poora offline mode |
| Arabic, Hindi, Bengali Phase 3 mein, Scope mein "baqi zabaanein" Phase 4 | Scope table mein Phase 3 ki row |
| Board ki rubric library Phase 4 mein, jab ke yahi khasiyat hai | Matric aur Inter ke 10 namoona rubrics Phase 2 mein; poori library Phase 4 |
| Gate 4 (EU AI Act) sab ke liye blocker | "EU mein bechne se pehle" ki shart |
| App English mein khulti thi | Admin organization ki default zabaan chunta hai (Urdu pilot = Urdu) |
| Sources: blog aur secondary site | Google ka apna page aur Chambers guide pehle; blog sirf mazeed tafseel |
| Urdu font Google Fonts se | Font app ke andar (SIL OFL): offline chalta hai, bachchon ka IP kisi teesri site ko nahi jata |

Teen mafroozay barqarar hain; koi ghalat ho to plan badlega.

- **Pehla bazaar Pakistan**, zabaanein Urdu aur English.
- **Students 18 se kam** (school), is liye bachchon wale sab qawaid lagu.
- **Phase 1 ka build mukammal** (yeh repo); asli school mein pilot abhi nahi hua.

## Market aur positioning

Aap ki jagah woh schools hain jahan kaam copy par hota hai, internet kamzor hai aur Google ka paid edition nahi hai.

Google ne April 2026 mein Gemini ko Classroom ki tamam supported zabanon mein khol diya (Google: https://workspaceupdates.googleblog.com/2026/04/gemini-in-google-classroom-is-now-available-in-all-Classroom-supported-languages.html). Written assignment par feedback ka draft (Google ka elaan: https://workspaceupdates.googleblog.com/2026/02/educators-now-get-help-drafting-personalized-guidance-on-written-assignments-with-AI.html) paid edition ya add-on mangta hai.

| Aap ki jagah | Kyun khali hai | Kya sabit karna hai |
|---|---|---|
| Haath ki likhi copy ki checking (Urdu aur English) | Bare tools typed kaam par bane hain; Urdu handwriting par koi study nahi mili | Pilot mein Claude aur teacher ke scores ka farq |
| Kam internet aur phone | Bare tools achhe connection aur laptop ke liye | Photo upload 2G/3G par chale, jawab device mein mehfooz rahe |
| Mukammal loop bina paid edition | Google ka AI feedback paid edition mein | School ki qeemat Google ke add-on se kam |
| Board ke pattern par marking | Bare tools mulk ke board ki marking scheme nahi jante | Matric/Inter ke namoona rubrics (Phase 2) |

"Ek hi app mein poora loop" ko khasiyat na kahein; Google, Canvas aur Microsoft ke paas pehle se hai.

## Scope: pehle kya, baad mein kya

| Hissa | Kab | Wajah |
|---|---|---|
| Assignment loop: assign, submit (typed, file, photo), Claude draft, teacher manzoori, dashboard | Phase 1 (bana) | App ka dil |
| Urdu aur English, RTL, Nastaliq font app ke andar | Phase 1 (bana) | Pilot Urdu mein |
| School ke accounts, CSV import, audit log | Phase 1 (bana) | Bachchon ke qawaid, manzoori ka saboot |
| App shell offline, jawab ki qatar | Phase 1 (bana) | Kamzor internet |
| Papers, projects, class ka link, student pre-check | Phase 2 | Wohi review engine |
| Matric/Inter ke 10 namoona rubrics | Phase 2 | Board pattern wali khasiyat jaldi sabit ho |
| SSO, Google Classroom/LTI, poora offline mode | Phase 3 | Bare schools |
| Arabic, Hindi, Bengali (apne test set ke saath) | Phase 3 | Doosre bazaar |
| App ke andar live video, class ka AI khulasa | Phase 3 | Pilot ke minutes dekh kar |
| Parent view, poori rubric library, baqi zabaanein | Phase 4 | Har zabaan apna test pass kare |
| AI proctoring (camera, chehra, jazbaat) | Kabhi nahi | EU mein taleemi idaron mein jazbaat pehchanna mana; bachchon ki nigrani |

## User requirements

| User | Kya chahiye | Priority | Phase |
|---|---|---|---|
| Teacher | Rubric par Claude ka draft score aur feedback, edit ya approve | Must | 1 ✔ |
| Teacher | Har student ka feedback alag release | Must | 1 ✔ |
| Teacher | Wahi jawab, wahi score (rubric aur sample jawab) | Must | 1 ✔ |
| Teacher | Haath ke likhe jawab par draft, har jawab teacher khud dekhe | Must | 1 ✔ |
| Teacher | Feedback apni zabaan mein bhi | Must | 1 ✔ |
| Student | Feedback Urdu ya English mein | Must | 1 ✔ |
| Student | Phone aur kam internet | Must | 1 ✔ |
| Student | Re-check ki darkhast | Must | 1 ✔ |
| Student | Jama karne se pehle Claude ke ishare (score nahi) | Should | 2 |
| School admin | Alag organization, class aur role; CSV | Must | 1 ✔ |
| School admin | Audit trail | Must | 1 ✔ |
| School admin | Organization ki default zabaan | Must | 1 ✔ |
| School admin | SSO, Google Classroom, LTI | Should | 3 |
| Parent | Sirf apne bachche ka approve hua nateeja | Later | 4 |

## Core loop aur AI review

Claude sirf draft likhta hai; number aur release hamesha teacher ke haath mein.

1. Teacher task banata hai: hidayat, rubric, har level ka sample jawab, zabaan.
2. Student jama karta hai: typed, file ya copy ki photo.
3. Claude har criterion ka number, wajah aur confidence deta hai (tay shuda JSON dhancha).
4. Teacher pehle apna andaza deta hai, phir draft khulta hai.
5. Teacher edit, approve ya wapas bhejta hai. Re-check aur flag wale jawab queue mein upar.
6. Student ko approve hua feedback milta hai, saath likha hota hai ke draft AI ne banaya aur teacher ne manzoor kiya.
7. Dashboard sirf approve hue scores ko final ginta hai.

| Design | Faisla |
|---|---|
| Anchoring | "Pehle main score karun" default ON |
| Farq napna | 10% jawab bina AI draft (blind); waqt ka muqabla |
| Consistency | Wahi model, prompt version, rubric; prompt version audit mein |
| Confidence flag | Dhundhli photo, `[unclear]`, rubric se bahar, zabaan ka shak, jawab mein AI ke liye hidayat |
| Non-native bias | Har term Claude aur teacher ke scores ka alag muqabla |
| Re-check | Pehla aur naya score dono audit mein |
| Audit trail | AI draft, teacher ki tabdeeli, final teeno alag |

### Pre-check (Phase 2)

Ek button, rubric ke mutabiq zyada se zyada teen ishare, score nahi. Har task par do baar. Log teacher aur admin dekh sakte hain. 13 se kam ke liye default band.

### Quality ke numbers (Gate 1)

| Paimana | Hadd |
|---|---|
| Claude ka total teacher ke total se 10% ke andar | Kam se kam 90% jawab |
| Farq 20% se zyada | 3% se kam |
| Bare farq wale jawab jo pehle se flag the | Kam se kam 70% |
| Teacher ki tabdeeli | Sirf napein |
| Teacher ke waqt ki bachat (blind ke muqable) | Kam se kam 30% (Gate 2) |

Har zabaan ke liye kam se kam 100 jawab. App ka Admin → Quality page yeh sab zabaan war dikhata hai.

## Haath ki likhi copies

Har haath ka likha jawab teacher khud dekhta hai.

1. Phone par photo seedhi aur chhoti hoti hai; dhundhli ya chhoti ho to dobara lene ko kaha jata hai.
2. Upar ka hissa (naam, roll number) phone par hi kat jata hai; Claude ko sirf jawab wala hissa aur code. Teacher ko poori photo.
3. Claude parhai (transcription) deta hai; na parh sake to `[unclear]`, andaza nahi.
4. Teacher photo aur parhai mila kar har criterion par "checked" lagata hai; is ke baghair approve nahi hota.

Diagram aur math par alag flag; pilot mein kamzor nikle to in par draft band.

Pilot se pehle 50-100 asli copies par test, Urdu aur English alag.

## Academic integrity

App "AI detector" score nahi deti.

- **Likhne ka record:** waqt, keystrokes, har paste ka size. Teacher ko dikhta hai, faisla nahi karta. Bara paste (200+ harf) par flag.
- **Sirf haath se likha:** task ki setting.
- **Zubani sawal:** har draft ke saath do follow-up sawal.
- **Milte julte jawab:** isi task ke jawab se 60%+ milta ho to flag.

**Prompt injection:** jawab `<student_answer>` mein data ke taur par; jawab mein grader ke liye hidayat mile to flag (English, Roman Urdu, Urdu); output tay shuda dhanche mein; hadd se bahar number code rad karta hai. Launch se pehle 30-50 aise jawab ka test set har prompt tabdeeli par.

## Projects, papers aur live classes (Phase 2 aur 3)

| Hissa | Faisla |
|---|---|
| Projects | Marhale, group, confidential member evaluation; "who did what" sirf files aur edits ki ginti, "Low" label nahi |
| Papers | Claude ke banaye sawal teacher ki manzoori tak pending; haath ke likhe paper ka har jawab teacher dekhe |
| Proctoring | Kisi phase mein nahi |
| Live class | Phase 2 mein sirf Zoom/Meet ka link; app ke andar video Phase 3 |
| Class ke baad | Transcript mein naam ki jagah code; recording default band; walidain ki ijazat ka record |

Video ka kharcha (Phase 3 ke faislay ke liye): 31 × 45 × 22 = 30,690 minute × $0.0035 ≈ $107 mahina ek class. Rate Zoom ke page par dobara dekhein.

## Zabaanein

Har user ki zabaan sirf Settings se badalti hai. Naye user ki zabaan organization ki default (admin chunta hai). Feedback ki zabaan student chunta hai; teacher task par badal sakta hai. Teacher ko wahi feedback apni zabaan mein bhi dikhta hai.

| Tier | Matlab | Shart |
|---|---|---|
| Full | Draft score aur feedback | Us zabaan par Gate 1 pass |
| Beta | Draft sirf feedback; score teacher | Pilot ki shuruat |
| Interface only | AI draft nahi | Test nahi hua (Roman Urdu abhi yahan) |

English aur Urdu Beta se shuru; Gate 1 ke baad admin Full karta hai.

## Accounts, security aur accessibility

Har record par organization id; har query isi se filter; har release se pehle automatic isolation test.

| Role | Dekhta hai | Kar sakta hai |
|---|---|---|
| Admin | Apni organization ke users, classes, usage, audit, quality | Accounts (CSV), settings, delete ka saboot |
| Teacher | Sirf apni classes aur jawab | Task, review, approve, return, class ke liye AI band |
| Student | Apne task aur approve hua feedback | Jama, re-check |
| Claude | Sirf bheja gaya jawab, code ke saath | Draft; kuch release nahi |

18 se kam ke accounts sirf school banata hai; class code sirf bane hue account ko jorta hai.

| Security | Haalat |
|---|---|
| HTTPS | Reverse proxy (Caddy), `docs/DEPLOY.md`; secure cookies aur HSTS |
| Files | AES-256-GCM encrypted; 5 minute ke signed links, user aur organization se bandhe |
| Login | Mazboot password, teacher/admin ke liye do-step (TOTP), login throttle database mein |
| Password reset | Email (SMTP) ya admin ka 1 ghante ka link |
| Backup | Rozana, aakhri 14; mahana restore test; offsite copy ka tareeqa |
| Data ki muddat | Organization ki setting (default 365 din); delete ka saboot |
| Hadsa | `docs/INCIDENT-PLAN.md` |
| Penetration test | Phase 2 se pehle |
| ISO 27001 / SOC 2 | Phase 4 |

**Accessibility:** WCAG 2.2 AA hadaf; keyboard, labels, focus ring; status rang ke saath lafz mein; Urdu ke liye zyada line height; extra waqt ka note assignment par bhi.

## Compliance

Yeh research ka khulasa hai, qanooni mashwara nahi. Har bazaar se pehle wakeel.

| Qanoon | Kya kehta hai | Karna |
|---|---|---|
| Pakistan | Jaame data protection qanoon abhi pass nahi; PECA 2016 aur aaeen ka privacy haq. Draft bill sensitive data mulk ke andar rakhne ko kehta hai (Chambers: https://practiceguides.chambers.com/practice-guides/data-protection-privacy-2026/pakistan) | GDPR ke mayar par design; wakeel ki likhit rai; school ke saath muahida |
| Anthropic: bachchon wali organizations | Age verification, content filtering, monitoring, AI ka elaan | App mein: school ke accounts, umar ka group, audit, AI ka elaan; baqi likh kar poochein |
| EU AI Act | Taleemi nataij wala AI high-risk, 2 December 2027 se (Orrick: https://www.orrick.com/en/Insights/2026/07/EU-AI-Act-Update-Digital-Omnibus-Finalizes-8-Compliance-Changes); jazbaat pehchanna February 2025 se mana | EU mein bechne se pehle dokumentation |
| UK | UK GDPR aur Children's Code | Alag jaanch |
| GDPR | Lawful basis, kam data, erasure | Delete ka tareeqa (bana) |
| FERPA (US) | Sirf taleemi maqsad | Data processing agreement |
| COPPA (US) | 13 se kam; naya rule April 22, 2026 se | Alag jaanch |
| India DPDP | 18 se kam bachcha; walidain ki ijazat | Wakeel |
| UAE | 13 se kam par sakhti; data bahar bhejne par pabandi | Data ki jagah tay karein |

**Data kahan:** apna database aur files jis region mein chahein (wada kar sakte hain). Claude API ki processing "global" ya "us" (https://platform.claude.com/docs/en/manage-claude/data-residency). Muahide mein dono likhein.

**Design faislay:** school banaye account; Claude ko kam se kam data (jawab, rubric, code); training nahi (Anthropic ke muahide se sabit); dastaveezat: privacy policy (`docs/PRIVACY-DRAFT.md` khaka), data processing agreement, retention schedule, sub-processors.

## Architecture aur kharcha

| Hissa | Faisla (bana) |
|---|---|
| Client | PWA: app shell offline, jawab IndexedDB qatar mein |
| Server | Node.js 22, Express, SQLite (pilot); Phase 3 par PostgreSQL |
| Review | Database queue; jama hote hi "mil gaya", draft peeche |
| AI | Claude API, structured output, rubric/samples prompt cache mein, model mana kare to fallback model, phir teacher ka manual raasta |
| Hadd | Organization ki mahana draft hadd; trial khatam ho to AI band |

Kharcha: ek jawab = input tokens × rate + output tokens × rate. App har mahine organization war tokens ginti hai (Admin → Home). Phase 0 mein typed, photo aur pre-check alag napein. Bachat: rubric cache, Batch API, chhoti photo, pre-check ki hadd.

## Pricing

| Model | Kis ke liye | Faisla |
|---|---|---|
| School licence (har student, har saal) | School, college | Asal model; muahida saath |
| School trial | Naye schools | Ek class, 60 din, muahide ke saath (app mein bana) |
| Free teacher plan | Sirf 18+ learners | Teacher apni API key laga sakta hai |
| AI credits | Hadd se upar | Licence se alag |
| Live video | Phase 3 | Minute ke hisaab se |

AI aur cloud kharcha qeemat ke 30% se kam. Pakistan mein rupay aur local payment.

## Roadmap aur gates

| Phase | Kya | Gate (agla phase tabhi) |
|---|---|---|
| 0: Tayyari | 100 asli copies par Claude vs teacher (Urdu, English alag); wakeel; Anthropic ki shartein; har jawab ka kharcha; asli API key | **Gate 1:** quality ki teeno hadd (90% / 3% / 70%) har pilot zabaan mein, 100+ jawab; wakeel ki likhit rai; Anthropic ki shartein poori; kharcha maloom |
| 1: Pilot (2-3 schools) | Yeh build: assignment loop, accounts, security, offline qatar | **Gate 2:** teacher ka waqt 30%+ bache; teachers har hafte istemal karein; koi privacy hadsa nahi; koi draft queue mein na khoye |
| 2: Papers, projects, link | Papers, projects, pre-check, 10 board rubrics, penetration test, doosre bazaar ki jaanch | **Gate 3:** papers par quality numbers; pen-test ke masail band; pehle paying schools |
| 3: Integration aur scale | SSO, Classroom/LTI, poora offline, video, Arabic/Hindi/Bengali, PostgreSQL | **Gate 4:** har naye bazaar ka compliance review; EU mein bechna ho to AI Act file 2 Dec 2027 se pehle |
| 4: Global | Baqi zabaanein, parent view, rubric library, ISO/SOC 2, admin analytics | – |

## Risks

| Khatra | Bachao |
|---|---|
| Urdu handwriting par ghalat parhai | Har jawab teacher dekhe; `[unclear]`; Beta tier |
| Anthropic ki shartein poori na hon | Pilot se pehle sab safeguards; likh kar poochein |
| Bachchon ka data leak | Kam data, code, encryption, isolation test, hadse ka plan |
| Bare players wahi feature free kar dein | Copy, kam internet, board pattern par tawajjo |
| AI se homework / prompt injection | Integrity hissa; injection test set |
| Teacher AI number maan le | Score-first default; blind 10% |
| Non-native bias | Term war muqabla |
| Kharcha zyada | Mahana hadd; cache; batch |
| Data region ka ghalat wada | Wada sirf database ka |
| Server kharab | Rozana backup, restore test, offsite copy |

## Aap ke faislay jo baqi hain

1. Pehla bazaar (mafrooza: Pakistan).
2. Pilot ke 2-3 schools aur teachers.
3. Gate 1 ke numbers (90% / 3% / 70%) theek hain ya nahi.
4. 100 asli copies kahan se aayengi.
5. Pakistan ka wakeel.
6. Team aur budget.
7. App ka naam (abhi "Assignment Review").
8. School licence ki qeemat (Phase 0 ke kharche ke baad).
9. Server kahan (Pakistan ya bahar) aur hosting company.
10. v1 mockup ke 10 masle: naya app un mockups par nahi bana, is liye yeh ab sirf design files ke liye hain.

## Sources

- Google: Gemini in Classroom (link upar), AI feedback ka elaan (link upar); edition ki mazeed tafseel: https://lilachbullock.com/gemini-for-teachers
- Anthropic data residency: https://platform.claude.com/docs/en/manage-claude/data-residency
- Pakistan: Chambers (link upar); https://www.recordinglaw.com/world-laws/world-data-privacy-laws/pakistan-data-privacy-laws/
- EU AI Act: Orrick (link upar); https://natlawreview.com/article/eu-digital-omnibus-ai-enters-force

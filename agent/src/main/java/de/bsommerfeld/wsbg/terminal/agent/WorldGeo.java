package de.bsommerfeld.wsbg.terminal.agent;

import java.util.Locale;
import java.util.Map;

import static java.util.Map.entry;

/**
 * Geographic FACT tables for the world-signals map (2026-07-15): a strongly
 * simplified world land outline (Natural Earth 110m, public domain) as one
 * equirectangular SVG path for the FROZEN map figure (small enough to live in
 * every report record forever), the 28 IMF PortWatch chokepoint positions
 * (from the provider's own reference layer), Natural Earth country centroids
 * for country-level geocoding (conflict events name countries, not
 * coordinates), and major US airports for FAA hazard geocoding. These are
 * facts, not curation — the no-curated-lists mandate governs OPINIONS about
 * relevance, never coordinates.
 *
 * <p>Projection contract (must match {@code web/js/map/world-basemap.js}):
 * viewBox 0 0 1000 500, x = (lon+180)/360*1000, y = (90-lat)/180*500.
 */
final class WorldGeo {

    static final int MAP_W = 1000;
    static final int MAP_H = 500;

    /** The frozen-figure land outline (coarse; the UI asset carries the finer one). */
    static final String LAND_PATH_MINI = "M335 472L327 475L321 475L316 473L322 473L328 473L332 471ZM58 471L52 471L49 470L45 468L52 468L57 470ZM375 467L379 470L375 473L371 474L366 475L360 475L353 475L350 474L356 472L360 470L365 467L370 466L375 467ZM225 450L231 450L227 451L220 451L216 450L221 450L225 450ZM310 447L306 451L299 451L294 451L300 448L301 443L305 441L309 446ZM337 428L332 429L328 430L327 434L323 435L318 438L322 441L326 444L330 450L331 455L324 457L317 460L313 461L306 462L299 463L295 463L290 463L285 463L291 465L295 466L288 467L284 468L287 472L291 473L297 473L302 474L306 475L311 476L317 476L324 477L329 478L334 479L338 481L346 479L351 478L357 478L362 477L369 477L375 477L381 478L387 476L394 476L399 475L404 475L410 474L416 474L421 473L412 470L406 471L401 471L402 467L406 466L411 466L417 464L424 462L427 462L434 462L438 461L444 460L451 459L456 457L457 453L463 452L468 450L475 448L479 449L484 447L488 449L492 448L498 448L502 448L508 447L514 446L520 445L524 445L528 446L533 446L537 444L541 445L547 444L553 444L560 445L566 446L572 446L578 445L583 444L589 443L593 441L597 441L603 442L607 444L611 442L617 441L623 440L627 438L632 438L636 436L641 436L646 433L651 433L657 433L661 436L666 437L671 439L676 438L681 438L686 438L691 439L694 442L688 445L689 450L694 451L699 449L703 446L707 444L713 443L717 442L722 439L726 438L730 437L735 437L741 437L745 435L749 437L754 436L760 437L764 437L769 437L774 436L779 436L786 432L790 433L795 436L800 436L806 435L810 434L816 433L819 434L824 435L829 437L836 437L840 435L845 435L850 435L855 435L860 435L866 434L872 434L877 432L882 436L889 436L895 436L901 436L906 437L910 439L917 440L921 441L927 441L931 441L936 443L942 443L947 445L952 446L958 447L965 447L971 448L976 449L972 453L967 455L961 457L956 460L954 464L963 468L959 469L955 470L949 470L945 474L949 477L955 479L959 480L963 481L969 481L979 483L989 484L995 485L1000 485L1000 500L0 500L0 485L8 485L11 484L16 485L19 484L28 483L32 484L36 485L44 486L50 486L61 487L69 486L81 487L87 488L95 487L102 486L92 485L83 484L73 482L75 478L71 477L64 475L71 475L78 475L82 476L86 475L91 474L85 470L79 470L74 470L69 470L63 468L60 464L64 465L69 464L73 464L80 465L83 464L90 463L94 462L97 459L103 459L111 459L114 458L118 458L124 456L128 457L133 456L136 457L140 457L144 456L148 457L152 457L156 457L160 457L164 457L167 457L174 456L180 456L185 456L191 457L198 458L205 459L209 458L213 458L217 459L220 459L215 456L212 452L218 452L221 452L227 453L232 454L236 454L240 454L246 454L250 454L254 453L261 453L267 454L274 455L280 454L284 454L288 455L292 455L298 454L305 453L309 453L313 451L311 447L310 443L312 438L317 434L321 432L325 430L329 429L334 428L337 426L341 426L337 428ZM312 400L315 401L319 402L315 403L311 404L306 403L299 401L293 397L299 399L302 400L307 396L312 400ZM904 363L908 364L912 364L911 368L907 371L904 367L902 363ZM981 364L983 367L981 372L976 373L974 378L970 380L966 379L968 373L971 371L975 368L978 364ZM985 350L989 354L993 355L991 360L989 365L985 365L986 361L985 356L984 352L981 348L984 348ZM639 288L640 292L638 297L636 303L635 307L633 312L632 316L629 320L625 320L621 315L620 311L623 306L622 301L623 297L627 294L633 291L636 286ZM899 288L902 291L904 295L906 299L910 304L913 307L916 312L921 317L925 322L927 328L926 332L925 336L923 340L920 344L919 348L917 353L912 355L908 357L904 357L899 358L895 357L891 356L888 352L884 349L880 348L883 343L879 345L874 342L869 339L865 337L860 338L856 339L850 339L845 342L841 344L837 344L833 344L829 347L824 347L820 345L821 341L821 338L819 333L818 329L815 324L816 319L816 315L817 310L821 310L825 307L830 306L836 305L840 301L842 296L845 293L849 290L853 288L857 291L860 292L861 288L865 284L868 284L873 283L877 283L879 287L876 291L881 294L885 297L889 299L892 296L894 292L893 288L894 284L895 281L898 284L899 288ZM802 269L807 269L813 269L818 272L813 273L807 273L802 271L796 270L795 266L800 268ZM873 253L873 258L879 256L884 255L889 257L896 259L902 261L906 265L910 267L909 271L913 275L917 277L911 278L907 275L902 271L898 273L892 275L886 272L882 273L884 267L878 263L871 260L867 258L872 257L867 256L864 254L868 251L872 252ZM848 246L844 249L836 249L836 254L843 252L838 255L840 259L842 263L838 263L836 257L834 261L835 265L832 260L831 256L833 250L838 247L841 248L847 245ZM794 266L789 264L785 262L782 258L778 252L775 247L771 243L768 239L765 235L771 235L775 240L780 244L785 246L788 250L790 255L795 259L794 266ZM827 245L826 250L824 254L823 261L819 261L815 259L810 258L806 258L806 254L803 249L805 244L809 245L814 241L817 237L823 233L827 232L831 235L827 239L828 244ZM851 227L851 233L845 233L843 228L839 231L842 227L846 226L851 224ZM726 233L722 231L721 227L723 223L727 229L726 233ZM837 199L840 203L838 208L844 212L841 213L836 212L834 208L833 205L835 199ZM298 195L303 195L308 196L304 199L299 199L295 200L299 198ZM279 187L282 187L287 191L292 193L288 195L284 195L281 190L277 189L273 188L268 188L264 189L269 186L274 186L279 187ZM543 144L542 148L538 147L535 146L538 144L543 144ZM892 147L891 150L886 154L881 154L877 157L870 155L864 156L865 163L861 159L864 155L868 152L874 151L880 146L886 145L889 140L890 136L894 139L892 144ZM900 127L904 127L900 131L893 131L889 135L890 130L894 126L898 126ZM157 115L151 114L147 112L143 110L148 110L152 111L156 114ZM344 109L347 113L351 113L354 118L350 120L344 120L341 118L335 118L338 114L341 109L345 107ZM899 109L902 114L898 113L896 117L899 122L895 122L894 117L895 112L895 108L894 102L898 103L899 109ZM481 105L476 106L472 106L473 100L477 98L481 97L483 100L481 105ZM492 87L494 92L497 98L501 102L505 104L504 108L498 109L493 110L487 110L485 106L487 101L491 102L487 98L484 94L484 89L488 87ZM263 68L267 69L273 71L277 73L271 73L266 73L262 75L258 73L260 70ZM460 65L462 69L459 71L451 73L445 73L437 72L438 68L432 68L439 66L443 67L447 66L455 65L460 65ZM14 65L23 64L28 67L21 68L17 71L11 70L5 68L0 67L0 58L7 61L14 63ZM248 57L252 58L255 61L260 61L262 56L266 56L270 57L274 58L274 62L268 66L265 66L261 67L257 70L250 72L245 75L238 81L237 86L241 87L244 91L248 91L253 92L257 94L264 96L268 97L272 102L278 108L282 104L280 100L286 95L287 91L282 87L284 81L283 77L290 77L295 77L301 79L307 80L308 86L312 88L316 87L321 82L326 88L328 94L332 95L339 97L344 101L345 105L341 107L337 108L329 111L323 110L318 110L313 112L310 114L306 117L309 116L315 114L319 113L320 119L325 123L329 123L334 122L324 126L318 129L321 124L317 124L311 127L305 129L303 132L299 135L295 136L299 136L294 137L292 142L289 147L288 141L288 147L290 151L285 154L280 157L275 161L274 165L275 169L278 175L277 180L273 178L270 174L270 169L264 168L260 166L254 166L251 169L245 168L241 167L237 168L232 171L229 176L229 181L228 186L229 191L232 195L237 198L240 199L244 198L248 196L249 192L254 190L258 190L257 194L256 199L255 204L259 206L263 206L266 207L269 210L268 214L267 218L269 222L273 225L278 224L282 224L287 226L290 221L294 219L298 217L302 216L301 221L301 225L302 220L306 216L310 220L316 220L320 222L325 220L329 223L331 226L336 228L340 232L345 234L350 234L356 237L360 245L359 249L365 251L371 253L375 254L379 257L385 258L389 258L393 260L397 263L401 264L403 269L402 275L397 281L393 286L392 294L391 298L390 304L387 308L384 312L380 314L376 315L371 317L365 322L365 325L364 330L359 336L355 340L352 344L347 347L344 347L339 346L341 350L340 356L335 358L330 358L327 363L323 364L319 364L321 368L319 374L313 377L315 381L313 385L309 390L309 394L303 397L298 399L292 395L290 390L290 385L294 380L290 380L293 373L298 368L294 370L294 366L295 361L296 356L298 349L300 344L302 340L301 336L301 330L304 321L304 316L305 309L305 305L305 301L296 295L291 292L288 288L286 284L283 279L280 273L276 268L274 263L277 259L276 255L277 251L279 247L281 244L286 239L285 234L284 230L282 226L277 227L274 229L270 227L266 224L262 222L261 218L256 214L252 213L248 211L244 210L239 206L235 205L232 207L228 205L223 204L218 201L212 199L208 196L207 192L206 188L203 184L199 180L196 177L193 173L188 170L186 164L181 162L181 166L185 170L188 174L191 179L193 183L195 187L190 182L188 178L184 176L180 173L181 169L177 164L175 160L171 156L166 154L162 150L160 146L156 142L154 138L155 133L155 129L156 124L155 120L154 116L158 117L153 111L146 109L145 105L141 103L137 99L133 96L129 91L125 88L120 88L111 85L104 83L100 83L95 82L88 81L84 84L79 86L79 81L72 85L69 90L61 93L57 96L52 96L47 98L42 99L48 96L54 94L59 92L62 88L58 88L55 86L50 87L49 83L45 84L41 82L40 78L45 74L49 73L53 73L49 71L42 71L38 70L33 68L37 66L43 65L51 66L45 64L41 61L37 60L43 59L47 57L53 54L58 53L65 52L69 52L77 53L81 54L90 55L95 55L101 55L105 56L114 57L118 58L123 57L131 57L135 56L139 55L143 56L151 57L154 55L158 57L163 56L167 57L173 58L177 59L184 60L180 61L185 62L192 62L198 63L200 59L205 59L210 61L218 62L222 62L227 62L233 60L237 61L235 56L232 52L239 51L246 55ZM183 47L188 47L192 49L197 48L201 50L207 48L210 53L214 54L219 55L215 57L210 59L206 58L197 59L189 59L185 60L180 58L174 56L180 55L184 55L188 55L182 54L176 54L172 54L177 52L173 52L168 51L173 48L180 46ZM260 47L264 46L271 45L276 48L281 49L290 49L294 51L299 51L309 54L314 58L309 59L315 61L320 62L324 64L328 64L322 69L315 66L311 66L317 70L319 76L316 75L309 73L313 75L309 77L303 75L296 72L292 70L284 72L289 69L295 68L298 63L292 60L286 59L283 56L279 56L274 56L264 56L258 55L254 54L249 49L254 46L262 45ZM221 45L230 45L231 51L227 52L222 51L215 49L221 48ZM241 48L235 50L233 46L237 44L243 44L249 45L244 47ZM165 52L158 53L150 50L156 45L162 43L166 44L173 44L179 46L169 49ZM903 40L891 42L886 43L880 41L886 39L893 39L903 40ZM226 37L227 42L223 42L215 40L222 37L226 37ZM199 38L203 39L195 42L188 43L184 43L189 41L177 42L173 41L177 38L187 38L192 40L197 40L193 38L198 37ZM660 54L649 53L643 51L646 48L651 45L655 43L661 40L670 38L679 38L684 37L689 36L680 40L671 41L662 44L658 46L654 49L660 54ZM237 36L246 37L250 39L256 40L260 40L264 40L270 39L275 40L278 42L272 43L269 43L261 43L255 43L251 43L243 42L239 38L233 38L237 36ZM177 34L172 38L167 39L163 39L159 39L163 36L169 35L173 35L177 34ZM797 36L809 37L815 38L806 43L811 45L815 46L821 45L830 46L842 47L848 46L853 46L857 47L860 52L865 53L872 52L877 51L882 52L889 51L890 48L915 49L925 53L936 53L942 53L947 57L951 57L956 56L961 57L966 57L971 59L973 55L982 56L988 56L996 57L1000 58L1000 70L996 71L993 71L997 74L993 76L985 78L978 81L974 82L969 82L962 84L958 84L954 84L950 88L953 94L949 96L945 99L940 103L936 108L933 102L932 96L933 92L940 89L945 85L950 82L955 80L957 76L952 79L945 82L935 79L928 84L924 86L920 87L916 84L904 85L895 86L886 91L875 98L880 98L884 101L889 99L893 103L891 108L889 115L885 119L880 125L876 128L871 131L864 132L860 134L857 138L857 143L860 148L859 153L854 154L850 148L846 144L848 141L841 140L836 142L839 138L835 137L831 141L826 142L830 145L836 145L840 146L835 150L831 153L835 157L839 162L838 166L838 172L834 175L832 178L826 184L822 187L817 188L811 190L807 193L801 190L796 193L794 197L798 204L802 208L804 213L803 218L798 221L792 226L792 222L787 220L785 216L780 215L776 220L776 224L779 229L784 233L787 237L787 241L790 245L785 245L781 241L779 237L779 233L776 230L773 227L774 222L774 218L774 214L772 209L771 205L765 206L763 202L760 196L757 193L755 188L751 187L747 189L742 190L740 194L736 196L731 201L727 205L723 206L723 212L722 217L722 221L717 225L713 225L711 221L709 217L707 211L704 206L703 200L702 197L702 191L698 192L692 189L689 184L684 179L679 180L675 180L671 180L666 179L659 179L657 175L652 176L646 173L641 170L639 166L635 167L634 171L637 175L640 179L643 178L643 183L648 183L652 181L656 178L658 183L661 184L665 187L664 191L660 195L657 200L652 203L648 204L642 208L638 209L634 211L630 213L626 214L621 215L620 211L619 206L618 203L615 198L612 194L608 189L607 184L603 181L601 176L598 172L597 168L595 173L590 167L593 173L595 177L599 184L602 188L603 192L604 198L608 203L611 207L614 210L618 214L619 217L623 221L627 220L632 219L636 218L640 218L641 221L639 228L637 231L635 235L629 242L622 247L617 253L613 257L610 262L608 266L609 271L610 275L612 280L613 285L613 289L611 295L607 298L601 302L597 305L598 309L599 314L597 318L592 320L591 324L590 329L586 333L580 339L576 342L572 344L566 344L560 345L556 347L551 344L550 341L549 335L545 329L542 325L541 321L540 316L540 311L536 305L533 300L532 296L534 291L535 288L538 283L538 280L536 275L536 271L534 267L531 261L526 256L525 252L526 247L527 244L526 240L521 238L516 238L514 234L510 233L505 233L499 235L495 237L491 236L487 236L482 237L478 238L472 234L468 231L464 228L462 224L459 220L455 217L453 213L451 209L454 205L455 200L455 196L453 192L455 187L457 182L459 179L464 173L468 172L471 169L473 163L474 160L479 156L483 152L487 152L493 152L497 151L501 149L509 148L513 148L517 147L521 148L526 146L531 147L530 151L528 155L532 158L536 159L542 160L546 163L550 165L554 165L556 160L560 159L564 159L569 161L574 162L579 164L584 163L588 163L592 164L595 163L597 159L600 154L599 149L595 149L590 150L585 148L580 148L575 145L574 142L576 138L580 138L587 136L593 133L598 133L603 135L607 136L612 136L616 133L612 130L607 127L602 124L606 122L602 120L597 121L601 124L598 125L594 127L590 124L588 120L584 122L580 125L578 130L578 133L576 137L571 137L566 137L565 141L567 145L563 146L559 145L556 141L554 137L552 133L549 131L544 129L541 125L537 123L535 128L539 131L544 133L549 136L548 140L545 144L544 140L539 137L534 134L529 131L525 127L521 129L513 129L509 130L506 135L502 136L499 141L498 145L494 148L491 148L486 149L482 147L478 148L475 144L475 140L476 136L475 132L481 129L485 129L490 129L495 129L497 122L492 118L488 117L496 115L504 111L509 107L513 103L517 101L522 101L523 96L523 92L527 90L530 94L528 98L533 99L538 100L545 99L552 98L555 98L559 97L559 92L563 90L567 92L568 88L572 84L578 85L573 82L568 83L564 84L559 81L558 76L562 73L569 70L562 67L559 71L555 73L550 76L548 80L552 83L547 87L546 92L541 94L536 96L533 90L531 87L523 88L520 89L516 87L514 78L524 74L529 71L534 67L541 62L546 60L553 56L559 55L564 55L568 53L573 53L578 52L587 54L583 55L589 56L594 57L601 58L612 61L611 66L607 67L594 65L597 71L601 72L610 71L617 65L622 66L621 63L628 60L629 65L633 64L640 61L649 59L654 60L659 60L663 59L670 58L676 57L680 58L690 61L689 57L685 54L690 50L694 47L702 48L700 52L702 58L703 62L698 66L702 65L706 63L708 58L704 57L703 52L708 50L712 52L721 49L726 51L724 45L728 45L735 45L741 45L745 41L751 40L758 40L766 38L775 38L780 38L790 34L795 35L791 36L797 36ZM551 29L560 31L553 32L549 34L544 37L538 35L531 31L537 28L542 29L547 28L551 29ZM571 27L576 28L572 29L564 29L556 29L551 28L557 26L561 27L571 27ZM642 26L638 27L632 28L625 26L630 26L634 26L639 25L643 26ZM778 31L772 31L764 30L759 29L753 27L760 25L767 24L772 26L778 28ZM258 29L253 33L248 33L242 32L236 30L231 27L235 25L243 24L247 26L252 26L256 27ZM310 19L317 19L323 20L328 20L321 22L315 23L318 24L312 25L307 26L302 28L297 29L286 30L290 30L284 34L278 36L284 36L276 38L269 38L261 38L257 38L251 38L256 36L264 35L260 33L256 32L263 31L259 27L266 27L273 26L266 26L257 26L252 25L246 23L250 22L258 21L262 20L269 21L275 19L280 19L288 19L298 19L304 19L310 19ZM425 18L442 20L437 21L426 21L411 22L423 22L431 23L436 22L443 24L456 22L465 23L455 26L444 27L451 27L445 31L449 36L444 36L440 37L445 39L446 44L440 44L435 46L438 49L433 48L439 51L435 54L429 52L434 55L438 55L430 58L423 60L415 61L409 62L405 65L399 67L393 68L389 68L387 72L381 76L381 80L376 83L371 81L366 81L361 77L357 73L355 69L351 66L353 60L357 59L352 58L348 55L352 53L357 54L352 52L347 52L346 47L341 42L337 41L330 39L324 38L316 39L310 39L302 36L309 35L315 35L303 34L296 33L307 31L317 29L311 27L323 24L327 24L333 22L341 22L350 22L360 21L367 22L371 22L376 23L370 22L379 19L389 19L393 18L403 18L425 18Z";

    private static final Map<String, double[]> CHOKEPOINTS = Map.ofEntries(
            entry("Bab el-Mandeb Strait", new double[]{12.79, 43.35}),
            entry("Balabac Strait", new double[]{7.41, 117.11}),
            entry("Bering Strait", new double[]{65.97, -165.55}),
            entry("Bohai Strait", new double[]{38.37, 120.9}),
            entry("Bosporus Strait", new double[]{41.17, 29.09}),
            entry("Cape of Good Hope", new double[]{-34.93, 20.88}),
            entry("Dover Strait", new double[]{51.03, 1.51}),
            entry("Gibraltar Strait", new double[]{35.94, -5.75}),
            entry("Kerch Strait", new double[]{45.27, 36.54}),
            entry("Korea Strait", new double[]{34.13, 129.21}),
            entry("Lombok Strait", new double[]{-8.42, 115.8}),
            entry("Luzon Strait", new double[]{20.49, 121.35}),
            entry("Magellan Strait", new double[]{-52.64, -69.59}),
            entry("Makassar Strait", new double[]{0.35, 119.26}),
            entry("Malacca Strait", new double[]{1.52, 102.67}),
            entry("Mindoro Strait", new double[]{12.47, 120.4}),
            entry("Mona Passage", new double[]{18.45, -67.71}),
            entry("Ombai Strait", new double[]{-8.4, 125.09}),
            entry("Oresund Strait", new double[]{55.51, 12.85}),
            entry("Panama Canal", new double[]{9.12, -79.77}),
            entry("Strait of Hormuz", new double[]{26.3, 56.86}),
            entry("Suez Canal", new double[]{30.59, 32.44}),
            entry("Sunda Strait", new double[]{-5.97, 105.78}),
            entry("Taiwan Strait", new double[]{24.72, 119.83}),
            entry("Torres Strait", new double[]{-9.86, 142.25}),
            entry("Tsugaru Strait", new double[]{41.33, 140.35}),
            entry("Windward Passage", new double[]{19.99, -73.7}),
            entry("Yucatan Channel", new double[]{21.82, -85.65}));

    private static final Map<String, double[]> COUNTRY_CENTROIDS = Map.ofEntries(
            entry("Afghanistan", new double[]{34.8, 67.8}),
            entry("Albania", new double[]{41.3, 20.1}),
            entry("Algeria", new double[]{29.7, 3.0}),
            entry("Angola", new double[]{-10.9, 17.4}),
            entry("Antarctica", new double[]{-73.0, -2.7}),
            entry("Argentina", new double[]{-37.0, -65.1}),
            entry("Armenia", new double[]{39.9, 45.3}),
            entry("Australia", new double[]{-23.8, 133.2}),
            entry("Austria", new double[]{47.6, 13.5}),
            entry("Azerbaijan", new double[]{40.4, 47.4}),
            entry("Bangladesh", new double[]{23.4, 90.6}),
            entry("Belarus", new double[]{53.3, 28.3}),
            entry("Belgium", new double[]{50.7, 4.4}),
            entry("Belize", new double[]{17.4, -88.6}),
            entry("Benin", new double[]{9.8, 2.3}),
            entry("Bhutan", new double[]{27.5, 90.6}),
            entry("Bolivia", new double[]{-16.3, -64.1}),
            entry("Bosnia and Herzegovina", new double[]{44.2, 17.9}),
            entry("Botswana", new double[]{-22.5, 24.5}),
            entry("Brazil", new double[]{-9.7, -56.9}),
            entry("Brunei", new double[]{4.7, 115.0}),
            entry("Bulgaria", new double[]{42.9, 24.7}),
            entry("Burkina Faso", new double[]{12.1, -1.9}),
            entry("Burundi", new double[]{-3.2, 30.0}),
            entry("Cambodia", new double[]{12.6, 104.9}),
            entry("Cameroon", new double[]{6.9, 13.2}),
            entry("Canada", new double[]{56.4, -91.1}),
            entry("Central African Republic", new double[]{6.3, 20.7}),
            entry("Chad", new double[]{13.0, 18.0}),
            entry("Chile", new double[]{-38.1, -71.4}),
            entry("Colombia", new double[]{4.5, -72.7}),
            entry("Costa Rica", new double[]{9.8, -84.2}),
            entry("Croatia", new double[]{44.8, 16.4}),
            entry("Cuba", new double[]{21.7, -79.7}),
            entry("Cyprus", new double[]{35.0, 33.2}),
            entry("Czech Republic", new double[]{49.8, 15.8}),
            entry("Democratic Republic of the Congo", new double[]{-3.8, 23.1}),
            entry("Denmark", new double[]{56.3, 9.6}),
            entry("Djibouti", new double[]{11.8, 42.5}),
            entry("Dominican Republic", new double[]{18.7, -70.5}),
            entry("East Timor", new double[]{-8.7, 125.9}),
            entry("Ecuador", new double[]{-1.6, -78.7}),
            entry("Egypt", new double[]{28.2, 31.7}),
            entry("El Salvador", new double[]{13.9, -88.9}),
            entry("Equatorial Guinea", new double[]{1.6, 10.1}),
            entry("Eritrea", new double[]{14.7, 39.6}),
            entry("Estonia", new double[]{58.7, 26.1}),
            entry("Eswatini", new double[]{-26.4, 31.4}),
            entry("Ethiopia", new double[]{9.0, 39.2}),
            entry("Falkland Islands", new double[]{-51.7, -59.6}),
            entry("Fiji", new double[]{-17.7, 178.0}),
            entry("Finland", new double[]{65.2, 25.9}),
            entry("France", new double[]{47.0, 3.3}),
            entry("French Southern and Antarctic Lands", new double[]{-49.1, 69.5}),
            entry("Gabon", new double[]{-0.3, 11.9}),
            entry("Georgia", new double[]{42.3, 43.4}),
            entry("Germany", new double[]{51.0, 10.7}),
            entry("Ghana", new double[]{8.3, -1.0}),
            entry("Greece", new double[]{39.8, 23.1}),
            entry("Greenland", new double[]{74.1, -41.0}),
            entry("Guatemala", new double[]{15.5, -90.3}),
            entry("Guinea", new double[]{10.4, -11.0}),
            entry("Guinea-Bissau", new double[]{12.0, -15.2}),
            entry("Guyana", new double[]{4.5, -58.9}),
            entry("Haiti", new double[]{18.8, -72.7}),
            entry("Honduras", new double[]{14.7, -86.3}),
            entry("Hungary", new double[]{47.4, 19.2}),
            entry("Iceland", new double[]{65.3, -19.3}),
            entry("India", new double[]{24.0, 83.6}),
            entry("Indonesia", new double[]{-2.0, 121.6}),
            entry("Iran", new double[]{33.4, 53.2}),
            entry("Iraq", new double[]{33.4, 44.4}),
            entry("Ireland", new double[]{53.5, -7.7}),
            entry("Israel", new double[]{32.0, 35.1}),
            entry("Italy", new double[]{43.1, 12.6}),
            entry("Ivory Coast", new double[]{7.9, -6.3}),
            entry("Jamaica", new double[]{18.2, -77.3}),
            entry("Japan", new double[]{35.7, 136.0}),
            entry("Jordan", new double[]{31.1, 36.7}),
            entry("Kazakhstan", new double[]{47.3, 65.3}),
            entry("Kenya", new double[]{1.1, 37.8}),
            entry("Kosovo", new double[]{42.5, 21.0}),
            entry("Kuwait", new double[]{29.3, 47.7}),
            entry("Kyrgyzstan", new double[]{41.4, 74.0}),
            entry("Laos", new double[]{18.3, 103.7}),
            entry("Latvia", new double[]{56.9, 24.9}),
            entry("Lebanon", new double[]{33.8, 35.9}),
            entry("Lesotho", new double[]{-29.6, 28.4}),
            entry("Liberia", new double[]{6.8, -9.1}),
            entry("Libya", new double[]{28.5, 16.6}),
            entry("Lithuania", new double[]{55.2, 24.0}),
            entry("Luxembourg", new double[]{49.8, 6.0}),
            entry("Madagascar", new double[]{-18.0, 47.0}),
            entry("Malawi", new double[]{-12.9, 34.1}),
            entry("Malaysia", new double[]{3.7, 114.8}),
            entry("Mali", new double[]{14.1, -5.8}),
            entry("Mauritania", new double[]{18.8, -12.4}),
            entry("Mexico", new double[]{23.9, -103.1}),
            entry("Moldova", new double[]{47.0, 28.5}),
            entry("Mongolia", new double[]{47.2, 104.6}),
            entry("Montenegro", new double[]{42.7, 19.4}),
            entry("Morocco", new double[]{28.8, -9.4}),
            entry("Mozambique", new double[]{-17.7, 35.0}),
            entry("Myanmar", new double[]{20.0, 97.2}),
            entry("Namibia", new double[]{-21.6, 17.9}),
            entry("Nepal", new double[]{28.2, 84.6}),
            entry("Netherlands", new double[]{52.1, 5.5}),
            entry("New Caledonia", new double[]{-21.2, 165.5}),
            entry("New Zealand", new double[]{-38.2, 175.5}),
            entry("Nicaragua", new double[]{13.1, -85.0}),
            entry("Niger", new double[]{15.5, 8.7}),
            entry("Nigeria", new double[]{9.7, 8.3}),
            entry("North Korea", new double[]{39.9, 127.4}),
            entry("North Macedonia", new double[]{41.7, 21.6}),
            entry("Norway", new double[]{66.2, 18.1}),
            entry("Oman", new double[]{20.9, 56.6}),
            entry("Pakistan", new double[]{30.8, 69.4}),
            entry("Palestine", new double[]{31.8, 35.2}),
            entry("Panama", new double[]{8.5, -80.3}),
            entry("Papua New Guinea", new double[]{-7.8, 146.2}),
            entry("Paraguay", new double[]{-23.3, -57.6}),
            entry("People's Republic of China", new double[]{37.3, 105.7}),
            entry("Peru", new double[]{-7.8, -74.1}),
            entry("Philippines", new double[]{15.4, 121.8}),
            entry("Poland", new double[]{51.7, 19.5}),
            entry("Portugal", new double[]{39.8, -8.0}),
            entry("Puerto Rico", new double[]{18.3, -66.4}),
            entry("Qatar", new double[]{25.3, 51.2}),
            entry("Republic of the Congo", new double[]{-0.9, 14.9}),
            entry("Romania", new double[]{45.9, 25.2}),
            entry("Russia", new double[]{59.1, 90.9}),
            entry("Rwanda", new double[]{-2.0, 29.9}),
            entry("Saudi Arabia", new double[]{24.0, 43.6}),
            entry("Senegal", new double[]{13.9, -14.6}),
            entry("Serbia", new double[]{43.9, 20.9}),
            entry("Sierra Leone", new double[]{8.6, -11.7}),
            entry("Slovakia", new double[]{48.8, 19.4}),
            entry("Slovenia", new double[]{46.1, 15.0}),
            entry("Solomon Islands", new double[]{-7.9, 159.1}),
            entry("Somalia", new double[]{6.6, 46.9}),
            entry("Somaliland", new double[]{10.5, 46.4}),
            entry("South Africa", new double[]{-28.6, 24.7}),
            entry("South Korea", new double[]{36.7, 127.5}),
            entry("South Sudan", new double[]{8.0, 30.1}),
            entry("Spain", new double[]{40.2, -4.5}),
            entry("Sri Lanka", new double[]{7.6, 80.9}),
            entry("Sudan", new double[]{12.8, 29.3}),
            entry("Suriname", new double[]{3.7, -55.9}),
            entry("Sweden", new double[]{62.7, 16.6}),
            entry("Switzerland", new double[]{46.8, 8.3}),
            entry("Syria", new double[]{35.1, 38.0}),
            entry("Taiwan", new double[]{23.9, 121.1}),
            entry("Tajikistan", new double[]{38.5, 70.8}),
            entry("Tanzania", new double[]{-6.5, 34.3}),
            entry("Thailand", new double[]{13.2, 100.7}),
            entry("The Bahamas", new double[]{24.5, -77.9}),
            entry("The Gambia", new double[]{13.5, -15.3}),
            entry("Togo", new double[]{8.9, 0.9}),
            entry("Trinidad and Tobago", new double[]{10.5, -61.5}),
            entry("Tunisia", new double[]{34.0, 9.8}),
            entry("Turkey", new double[]{38.4, 36.9}),
            entry("Turkish Republic of Northern Cyprus", new double[]{35.2, 33.4}),
            entry("Turkmenistan", new double[]{39.3, 58.5}),
            entry("Uganda", new double[]{1.3, 32.0}),
            entry("Ukraine", new double[]{48.7, 30.5}),
            entry("United Arab Emirates", new double[]{24.2, 54.2}),
            entry("United Kingdom", new double[]{53.9, -3.1}),
            entry("United States of America", new double[]{38.3, -90.4}),
            entry("Uruguay", new double[]{-32.7, -56.1}),
            entry("Uzbekistan", new double[]{41.2, 65.3}),
            entry("Vanuatu", new double[]{-15.4, 166.9}),
            entry("Venezuela", new double[]{7.2, -66.9}),
            entry("Vietnam", new double[]{16.7, 105.9}),
            entry("Western Sahara", new double[]{24.8, -12.0}),
            entry("Yemen", new double[]{15.5, 46.5}),
            entry("Zambia", new double[]{-12.8, 28.0}),
            entry("Zimbabwe", new double[]{-18.9, 29.7}));

    private static final Map<String, double[]> US_AIRPORTS = Map.ofEntries(
            entry("ATL", new double[]{33.64, -84.43}),
            entry("LAX", new double[]{33.94, -118.41}),
            entry("ORD", new double[]{41.97, -87.91}),
            entry("DFW", new double[]{32.9, -97.04}),
            entry("DEN", new double[]{39.86, -104.67}),
            entry("JFK", new double[]{40.64, -73.78}),
            entry("SFO", new double[]{37.62, -122.38}),
            entry("SEA", new double[]{47.45, -122.31}),
            entry("LAS", new double[]{36.08, -115.15}),
            entry("MCO", new double[]{28.43, -81.31}),
            entry("MIA", new double[]{25.8, -80.29}),
            entry("EWR", new double[]{40.69, -74.17}),
            entry("PHX", new double[]{33.43, -112.01}),
            entry("IAH", new double[]{29.98, -95.34}),
            entry("BOS", new double[]{42.36, -71.01}),
            entry("MSP", new double[]{44.88, -93.22}),
            entry("DTW", new double[]{42.21, -83.35}),
            entry("FLL", new double[]{26.07, -80.15}),
            entry("LGA", new double[]{40.78, -73.87}),
            entry("PHL", new double[]{39.87, -75.24}),
            entry("CLT", new double[]{35.21, -80.94}),
            entry("DCA", new double[]{38.85, -77.04}),
            entry("IAD", new double[]{38.94, -77.46}),
            entry("SLC", new double[]{40.79, -111.98}),
            entry("BWI", new double[]{39.18, -76.67}),
            entry("SAN", new double[]{32.73, -117.19}),
            entry("TPA", new double[]{27.98, -82.53}),
            entry("MDW", new double[]{41.79, -87.75}),
            entry("HNL", new double[]{21.32, -157.92}),
            entry("PDX", new double[]{45.59, -122.6}),
            entry("STL", new double[]{38.75, -90.37}),
            entry("AUS", new double[]{30.19, -97.67}),
            entry("BNA", new double[]{36.13, -86.67}),
            entry("MEM", new double[]{35.04, -89.98}),
            entry("ANC", new double[]{61.17, -149.98}),
            entry("CVG", new double[]{39.05, -84.66}));

    private WorldGeo() {
    }

    /** x/y in map coordinates for a lat/lon, viewBox 0 0 1000 500. */
    static double[] project(double lat, double lon) {
        return new double[]{(lon + 180.0) / 360.0 * MAP_W, (90.0 - lat) / 180.0 * MAP_H};
    }

    /** Chokepoint position by PortWatch name, or null. */
    static double[] chokepoint(String portName) {
        return portName == null ? null : CHOKEPOINTS.get(portName);
    }

    /** A country-level geocoder hit: matched country name + centroid. */
    record Located(String country, double lat, double lon) {
    }

    /**
     * The deterministic country-level geocoder for conflict/outbreak lines:
     * the LONGEST country name (English, Natural Earth spelling) contained in
     * the text wins (so "South Sudan" beats "Sudan"), or null when no country
     * is named — honest coarse geocoding, marked as country-level downstream.
     */
    static Located locate(String text) {
        if (text == null || text.isBlank()) return null;
        String hay = text.toLowerCase(Locale.ROOT);
        String bestName = null;
        double[] best = null;
        for (Map.Entry<String, double[]> e : COUNTRY_CENTROIDS.entrySet()) {
            String name = e.getKey().toLowerCase(Locale.ROOT);
            if ((bestName == null || name.length() > bestName.length())
                    && hay.contains(name)) {
                bestName = e.getKey();
                best = e.getValue();
            }
        }
        return best == null ? null : new Located(bestName, best[0], best[1]);
    }

    /** Airport position by IATA code, or null (FAA hazard texts lead with the code). */
    static double[] airport(String iata) {
        return iata == null ? null : US_AIRPORTS.get(iata.toUpperCase(Locale.ROOT));
    }
}

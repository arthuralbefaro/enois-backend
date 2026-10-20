-- theme_id precisa caber o id de um tema publicado na galeria da comunidade.
--
-- Os temas hardcoded da colecao tem slug curto ('aero-glass', 'caravana-do-vento'), e os 32
-- caracteres originais chegavam. Um tema da galeria nasce de "theme_" + UUID sem tracos: 38.
-- Sem alargar, a preferencia da conta aceitava o tema na validacao e morria no INSERT.
--
-- Renumerada para V102: V99-V101 pertencem ao feat/jogos-diarios, e coincidir de novo custaria
-- um rebuild limpo do banco de teste para renumerar.
alter table user_preferences modify column theme_id varchar(64) not null;

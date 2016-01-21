/*
 * Copyright (c) 2012-2016, b3log.org & hacpai.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.b3log.symphony;

import java.util.List;
import java.util.ResourceBundle;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletRequestEvent;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionEvent;
import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.event.EventManager;
import org.b3log.latke.ioc.LatkeBeanManager;
import org.b3log.latke.ioc.Lifecycle;
import org.b3log.latke.logging.Level;
import org.b3log.latke.logging.Logger;
import org.b3log.latke.model.Role;
import org.b3log.latke.model.User;
import org.b3log.latke.repository.Transaction;
import org.b3log.latke.repository.jdbc.JdbcRepository;
import org.b3log.latke.repository.jdbc.util.JdbcRepositories;
import org.b3log.latke.service.ServiceException;
import org.b3log.latke.servlet.AbstractServletListener;
import org.b3log.latke.util.MD5;
import org.b3log.latke.util.Requests;
import org.b3log.latke.util.StaticResources;
import org.b3log.latke.util.Stopwatchs;
import org.b3log.latke.util.Strings;
import org.b3log.symphony.event.ArticleNotifier;
import org.b3log.symphony.event.CommentNotifier;
import org.b3log.symphony.model.Article;
import org.b3log.symphony.model.Option;
import org.b3log.symphony.model.UserExt;
import org.b3log.symphony.repository.OptionRepository;
import org.b3log.symphony.repository.UserRepository;
import org.b3log.symphony.service.ArticleMgmtService;
import org.b3log.symphony.service.OptionQueryService;
import org.b3log.symphony.service.UserMgmtService;
import org.b3log.symphony.service.UserQueryService;
import org.b3log.symphony.util.Symphonys;
import org.json.JSONObject;

/**
 * Symphony servlet listener.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding</a>
 * @version 2.8.3.5, Jan 21, 2016
 * @since 0.2.0
 */
public final class SymphonyServletListener extends AbstractServletListener {

    /**
     * Symphony version.
     */
    public static final String VERSION = "1.3.0";

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(SymphonyServletListener.class.getName());

    /**
     * JSONO print indent factor.
     */
    public static final int JSON_PRINT_INDENT_FACTOR = 4;

    /**
     * Bean manager.
     */
    private LatkeBeanManager beanManager;

    @Override
    public void contextInitialized(final ServletContextEvent servletContextEvent) {
        Stopwatchs.start("Context Initialized");
        Latkes.setScanPath("org.b3log.symphony");
        super.contextInitialized(servletContextEvent);

        // del this after done TODO: https://github.com/b3log/symphony/issues/98
        final String skinDirName = Symphonys.get("skinDirName");
        Latkes.loadSkin(skinDirName);

        beanManager = Lifecycle.getBeanManager();

        // Init database if need
        initDB();

        // Register event listeners
        final EventManager eventManager = beanManager.getReference(EventManager.class);

        final ArticleNotifier articleNotifier = beanManager.getReference(ArticleNotifier.class);
        eventManager.registerListener(articleNotifier);

        final CommentNotifier commentNotifier = beanManager.getReference(CommentNotifier.class);
        eventManager.registerListener(commentNotifier);

        LOGGER.info("Initialized the context");

        Stopwatchs.end();
        LOGGER.log(Level.DEBUG, "Stopwatch: {0}{1}", new Object[]{Strings.LINE_SEPARATOR, Stopwatchs.getTimingStat()});
        Stopwatchs.release();
    }

    @Override
    public void contextDestroyed(final ServletContextEvent servletContextEvent) {
        super.contextDestroyed(servletContextEvent);

        LOGGER.info("Destroyed the context");
    }

    @Override
    public void sessionCreated(final HttpSessionEvent httpSessionEvent) {
    }

    @Override
    public void sessionDestroyed(final HttpSessionEvent httpSessionEvent) {
        final HttpSession session = httpSessionEvent.getSession();

        final Object userObj = session.getAttribute(User.USER);
        if (null != userObj) { // User logout
            final JSONObject user = (JSONObject) userObj;

            final UserMgmtService userMgmtService = beanManager.getReference(UserMgmtService.class);

            try {
                userMgmtService.updateOnlineStatus(user.optString(Keys.OBJECT_ID), "", false);
            } catch (final ServiceException e) {
                LOGGER.log(Level.ERROR, "Changes user online from [true] to [false] failed", e);
            }
        }

        super.sessionDestroyed(httpSessionEvent);
    }

    @Override
    public void requestInitialized(final ServletRequestEvent servletRequestEvent) {
        final HttpServletRequest httpServletRequest = (HttpServletRequest) servletRequestEvent.getServletRequest();

        httpServletRequest.setAttribute(Keys.TEMAPLTE_DIR_NAME, Symphonys.get("skinDirName"));

        if (Requests.searchEngineBotRequest(httpServletRequest)) {
            LOGGER.log(Level.DEBUG, "Request made from a search engine[User-Agent={0}]", httpServletRequest.getHeader("User-Agent"));
            httpServletRequest.setAttribute(Keys.HttpRequest.IS_SEARCH_ENGINE_BOT, true);

            return;
        }

        httpServletRequest.setAttribute(Keys.HttpRequest.IS_SEARCH_ENGINE_BOT, false);

        if (StaticResources.isStatic(httpServletRequest)) {
            return;
        }

        // Gets the session of this request
        final HttpSession session = httpServletRequest.getSession();
        LOGGER.log(Level.TRACE, "Gets a session[id={0}, remoteAddr={1}, User-Agent={2}, isNew={3}]",
                new Object[]{session.getId(), httpServletRequest.getRemoteAddr(), httpServletRequest.getHeader("User-Agent"),
                    session.isNew()});

        // Online visitor count
        OptionQueryService.onlineVisitorCount(httpServletRequest);

        resolveSkinDir(httpServletRequest);
    }

    @Override
    public void requestDestroyed(final ServletRequestEvent servletRequestEvent) {
        super.requestDestroyed(servletRequestEvent);
        Stopwatchs.release();
    }

    /**
     * Initializes database if need.
     */
    private void initDB() {
        final UserQueryService userQueryService = beanManager.getReference(UserQueryService.class);

        try {
            final List<JSONObject> admins = userQueryService.getAdmins();
            JdbcRepository.dispose();

            if (null != admins && !admins.isEmpty()) { // Initialized already
                return;
            }
        } catch (final ServiceException e) {
            LOGGER.log(Level.ERROR, "Check init error", e);

            System.exit(0);
        }

        LOGGER.info("Initializing Sym....");

        final OptionRepository optionRepository = beanManager.getReference(OptionRepository.class);
        final ArticleMgmtService articleMgmtService = beanManager.getReference(ArticleMgmtService.class);
        final UserMgmtService userMgmtService = beanManager.getReference(UserMgmtService.class);

        try {
            LOGGER.log(Level.INFO, "Database [{0}], creates all tables", Latkes.getRuntimeDatabase());

            final List<JdbcRepositories.CreateTableResult> createTableResults = JdbcRepositories.initAllTables();
            for (final JdbcRepositories.CreateTableResult createTableResult : createTableResults) {
                LOGGER.log(Level.INFO, "Creates table result[tableName={0}, isSuccess={1}]",
                        new Object[]{createTableResult.getName(), createTableResult.isSuccess()});
            }

            final Transaction transaction = optionRepository.beginTransaction();

            // Init statistic
            JSONObject option = new JSONObject();
            option.put(Keys.OBJECT_ID, Option.ID_C_STATISTIC_MEMBER_COUNT);
            option.put(Option.OPTION_VALUE, "0");
            option.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_STATISTIC);
            optionRepository.add(option);

            option = new JSONObject();
            option.put(Keys.OBJECT_ID, Option.ID_C_STATISTIC_CMT_COUNT);
            option.put(Option.OPTION_VALUE, "0");
            option.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_STATISTIC);
            optionRepository.add(option);

            option = new JSONObject();
            option.put(Keys.OBJECT_ID, Option.ID_C_STATISTIC_ARTICLE_COUNT);
            option.put(Option.OPTION_VALUE, "0");
            option.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_STATISTIC);
            optionRepository.add(option);

            option = new JSONObject();
            option.put(Keys.OBJECT_ID, Option.ID_C_STATISTIC_TAG_COUNT);
            option.put(Option.OPTION_VALUE, "0");
            option.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_STATISTIC);
            optionRepository.add(option);

            option = new JSONObject();
            option.put(Keys.OBJECT_ID, Option.ID_C_STATISTIC_MAX_ONLINE_VISITOR_COUNT);
            option.put(Option.OPTION_VALUE, "0");
            option.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_STATISTIC);
            optionRepository.add(option);

            // Init misc
            option = new JSONObject();
            option.put(Keys.OBJECT_ID, Option.ID_C_MISC_ALLOW_REGISTER);
            option.put(Option.OPTION_VALUE, "0");
            option.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_MISC);
            optionRepository.add(option);

            option = new JSONObject();
            option.put(Keys.OBJECT_ID, Option.ID_C_MISC_ALLOW_ADD_ARTICLE);
            option.put(Option.OPTION_VALUE, "0");
            option.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_MISC);
            optionRepository.add(option);

            option = new JSONObject();
            option.put(Keys.OBJECT_ID, Option.ID_C_MISC_ALLOW_ADD_COMMENT);
            option.put(Option.OPTION_VALUE, "0");
            option.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_MISC);
            optionRepository.add(option);

            transaction.commit();

            // Init admin
            final ResourceBundle init = ResourceBundle.getBundle("init");
            final JSONObject admin = new JSONObject();
            admin.put(User.USER_EMAIL, init.getString("admin.email"));
            admin.put(User.USER_NAME, init.getString("admin.name"));
            admin.put(User.USER_PASSWORD, MD5.hash(init.getString("admin.password")));
            admin.put(User.USER_ROLE, Role.ADMIN_ROLE);
            admin.put(UserExt.USER_STATUS, UserExt.USER_STATUS_C_VALID);
            admin.put(UserExt.USER_TEAM, "Owner");
            final String adminId = userMgmtService.addUser(admin);
            admin.put(Keys.OBJECT_ID, adminId);

            // Hello World!
            final JSONObject article = new JSONObject();
            article.put(Article.ARTICLE_TITLE, init.getString("helloWorld.title"));
            article.put(Article.ARTICLE_TAGS, init.getString("helloWorld.tags"));
            article.put(Article.ARTICLE_CONTENT, init.getString("helloWorld.content"));
            article.put(Article.ARTICLE_EDITOR_TYPE, 0);
            article.put(Article.ARTICLE_AUTHOR_EMAIL, admin.optString(User.USER_EMAIL));
            article.put(Article.ARTICLE_AUTHOR_ID, admin.optString(Keys.OBJECT_ID));
            articleMgmtService.addArticle(article);

            LOGGER.info("Initialized Sym");
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Creates database tables failed", e);

            System.exit(0);
        }
    }

    /**
     * Resolve skin (template) for the specified HTTP servlet request.
     *
     * @param request the specified HTTP servlet request
     */
    private void resolveSkinDir(final HttpServletRequest request) {
        try {
            final UserQueryService userQueryService = beanManager.getReference(UserQueryService.class);
            final UserRepository userRepository = beanManager.getReference(UserRepository.class);

            JSONObject user = userQueryService.getCurrentUser(request);
            if (null == user) {
                final Cookie[] cookies = request.getCookies();
                if (null == cookies || 0 == cookies.length) {
                    return;
                }

                try {
                    for (final Cookie cookie : cookies) {
                        if (!"b3log-latke".equals(cookie.getName())) {
                            continue;
                        }

                        final JSONObject cookieJSONObject = new JSONObject(cookie.getValue());

                        final String userId = cookieJSONObject.optString(Keys.OBJECT_ID);
                        if (Strings.isEmptyOrNull(userId)) {
                            break;
                        }

                        user = userRepository.get(userId);
                        if (null == user) {
                            return;
                        } else {
                            break;
                        }
                    }
                } catch (final Exception e) {
                    LOGGER.warn(e.getMessage());
                }

                if (null == user) {
                    return;
                }
            }

            request.setAttribute(Keys.TEMAPLTE_DIR_NAME, user.optString(UserExt.USER_SKIN));
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Resolves skin failed", e);
        }
    }
}
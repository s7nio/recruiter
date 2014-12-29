package de.th.wildau.recruiter.web;

import java.util.List;

import javax.annotation.PostConstruct;
import javax.faces.bean.ViewScoped;
import javax.inject.Inject;
import javax.inject.Named;

import lombok.Getter;
import de.th.wildau.recruiter.ejb.model.Article;
import de.th.wildau.recruiter.ejb.model.Price;
import de.th.wildau.recruiter.ejb.service.ArticleService;

@Named
@ViewScoped
public class DashboardHome extends AbstractHome {

	private static final long serialVersionUID = -6155008258787828330L;

	@Getter
	private List<Article> articles;

	@Inject
	private ArticleService articleService;

	@Getter
	private List<Price> prices;

	@PostConstruct
	public void init() {
		this.articles = this.articleService.findMyArticles();
		this.prices = this.articleService.findMyPrices();
	}

}